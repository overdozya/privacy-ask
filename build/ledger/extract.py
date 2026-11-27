"""처리방침 첨부 PDF의 괘선 표를 행 단위로 뽑는다 (표준 라이브러리 + pdftoppm/pdftotext/pdfinfo).

실험 03의 셀 단위 추출기(tools/ledger_build.py)를 일반화했다. 바뀐 점과 이유:
- 칸을 '열마다 가로줄로 자른 조각'이 아니라 '격자 칸을 선이 없는 쪽으로 합친 덩어리'로 만든다.
  그래야 여러 열에 걸친 칸(첨부5-0130 '[수집되는 개인정보의 판매자 제공]', 첨부4의 노란 줄)이
  한 칸으로 남는다. 실험 03은 이 칸을 열마다 잘라서 글이 섞였다.
- 쪽 안의 표를 따로 찾는다(첨부10은 한 쪽에 표가 둘이다). 열 이름은 표마다 머리 행에서 정한다
  (첨부4처럼 두 줄 머리도 '위탁 업무 내용 > 재위탁 협력 회사'로 펼친다).
- 표 밖 글(제목, 표 아래 ※ 비고 등)은 버리지 않고 따로 돌려준다(못 뽑은 글 목록의 재료).

방법
1. 쪽을 144dpi 회색조로 그린다. 어두운 점(<150)이 가로로 40px 넘게 이어지면 가로줄 후보,
   세로로 16px 넘게 이어지고 한쪽 끝이 가로줄에 닿으면 세로줄 후보다. 글자 획은 이 조건을 못 넘는다.
2. 후보 x·y로 격자를 만들고, 이웃 격자 칸 사이 경계에 실제 선이 80% 넘게 그려져 있지 않으면 합친다.
   쪽 가장자리에 닿는 덩어리는 표 밖이다.
3. 단어(pdftotext -bbox)는 가운데 점이 들어간 칸에 넣는다.
4. 표 조각의 맨 위 행 묶음이 머리 행이다. 다음 쪽 조각이 같은 세로줄을 쓰면 이어지는 표다.
   쪽 맨 위 칸이 비었거나 기준 열 칸이 비었으면 앞 쪽 칸이 이어진 것으로 본다(실험 03과 같은 규칙).
5. 행은 표 안 가로줄마다 자르고, 열마다 그 행을 덮는 칸을 모은다. 이웃 행이 모든 열에서 같은 칸이면 한 행이다.
"""
import bisect
import hashlib
import html
import re
import subprocess
import tempfile
from pathlib import Path

EXTRACTOR_VERSION = "ledger-extract-13"
DPI = 144
S = DPI / 72.0
DARK = 150
H_MIN = 40      # px, 가로줄 후보 최소 길이(약 20pt)
V_MIN = 26      # px, 세로줄 후보 최소 길이(약 13pt). 굵은 글자 획(최대 약 20px)보다 길어야 한다
END_RUN = 60    # px, 이보다 긴 세로줄의 끝만 가로 경계 후보로 쓴다(쪽이 끊긴 표의 열린 윗변·아랫변)
MERGE = 6       # px, 이보다 가까운 선 후보는 한 선
TOUCH = 4       # px, 세로줄 끝이 가로줄에 닿았다고 보는 거리
COVER = 0.8     # 경계 길이 중 선이 그려져 있어야 하는 비율
COL_TOL = 8     # px, 쪽 사이 세로줄 위치 허용 차이

BULLET_ONLY = re.compile(r"^[•●○▪■∙·ㆍ-]$")
_MASK = bytes(1 if v < DARK else 0 for v in range(256))


def page_count(pdf):
    out = subprocess.run(["pdfinfo", str(pdf)], capture_output=True, text=True, check=True).stdout
    return int(re.search(r"^Pages:\s+(\d+)", out, re.M).group(1))


def file_sha256(pdf):
    return hashlib.sha256(Path(pdf).read_bytes()).hexdigest()


def render(pdf, page, tmp):
    base = Path(tmp) / f"r{page}"
    subprocess.run(["pdftoppm", "-gray", "-r", str(DPI), "-f", str(page), "-l", str(page), str(pdf), str(base)],
                   check=True)
    pgm = next(Path(tmp).glob(f"r{page}*.pgm"))
    data = pgm.read_bytes()
    pgm.unlink()
    m = re.match(rb"P5\s+(\d+)\s+(\d+)\s+(\d+)\s", data)
    w, h = int(m.group(1)), int(m.group(2))
    return w, h, data[m.end():m.end() + w * h]


def words(pdf, page):
    out = subprocess.run(["pdftotext", "-f", str(page), "-l", str(page), "-bbox", str(pdf), "-"],
                         capture_output=True, text=True, check=True).stdout
    res = []
    for m in re.finditer(r'<word xMin="([\d.]+)" yMin="([\d.]+)" xMax="([\d.]+)" yMax="([\d.]+)">(.*?)</word>', out):
        x0, y0, x1, y1 = map(float, m.groups()[:4])
        res.append({"x0": x0, "y0": y0, "x1": x1, "y1": y1, "cx": (x0 + x1) / 2 * S, "cy": (y0 + y1) / 2 * S,
                    "t": html.unescape(m.group(5))})
    return res


def join_words(ws):
    """단어를 줄로 묶는다(실험 03과 같은 규칙: 윗변 차이 3pt 이내면 한 줄)."""
    ws = sorted(ws, key=lambda w: (w["y0"], w["x0"]))
    lines, cur, cy = [], [], None
    for w in ws:
        if cy is None or abs(w["y0"] - cy) > 3:
            if cur:
                lines.append(" ".join(x["t"] for x in sorted(cur, key=lambda z: z["x0"])))
            cur, cy = [w], w["y0"]
        else:
            cur.append(w)
    if cur:
        lines.append(" ".join(x["t"] for x in sorted(cur, key=lambda z: z["x0"])))
    return "\n".join(lines)


def cluster(vals):
    out = []
    for v in sorted(vals):
        if out and v - out[-1][-1] <= MERGE:
            out[-1].append(v)
        else:
            out.append([v])
    return [round(sum(c) / len(c)) for c in out]


def _prefix(flags):
    p = [0]
    for f in flags:
        p.append(p[-1] + f)
    return p


def _ends_dark(p, a, b):
    """경계 양 끝 6px이 어두운가: 표의 선은 양쪽 선과 만나고, 밑줄·글자 획은 칸 안쪽 여백에서 끝난다."""
    n = len(p) - 1
    a1, a2 = max(0, a + 1), min(n, a + 7)
    b1, b2 = max(0, b - 6), min(n, b)
    return (p[a2] - p[a1]) >= 4 and (p[b2] - p[b1]) >= 4


class Page:
    """한 쪽의 격자 칸과 단어."""

    def __init__(self, pdf, page, tmp, extra_lines=()):
        self.page = page
        w, h, px = render(pdf, page, tmp)
        self.w, self.h = w, h
        mask = px.translate(_MASK)
        if extra_lines:
            # 원본에 칸 경계선이 빠진 곳(사람이 확인한 곳)에 가로줄을 그려 넣는다: [(y, x0, x1) pt]
            m = bytearray(mask)
            for y, x0, x1 in extra_lines:
                for yy in (round(y * S), round(y * S) + 1):
                    m[yy * w + round(x0 * S): yy * w + round(x1 * S) + 1] = b"\x01" * (round(x1 * S) - round(x0 * S) + 1)
            mask = bytes(m)
        hsegs = []
        hpat = re.compile(rb"\x01{%d,}" % H_MIN)
        for y in range(h):
            for m in hpat.finditer(mask, y * w, (y + 1) * w):
                hsegs.append((y, m.start() - y * w, m.end() - y * w))
        ys = cluster(y for y, _, _ in hsegs)
        # 가로줄 후보를 픽셀 행별 구간으로 모아 둔다(세로줄 끝 닿음 검사용)
        by_y = {}
        for y, a, b in hsegs:
            by_y.setdefault(y, []).append((a, b))

        def touches(x, y):
            for yy in range(y - TOUCH, y + TOUCH + 1):
                for a, b in by_y.get(yy, ()):
                    if a - TOUCH <= x <= b + TOUCH:
                        return True
            return False

        vpat = re.compile(rb"\x01{%d,}" % V_MIN)
        vx = []
        for x in range(w):
            col = mask[x::w]
            for m in vpat.finditer(col):
                # 가로줄에 닿거나, 글자 획이 될 수 없을 만큼 길면(첨부5 2쪽 왼쪽 테두리처럼 가로줄 없는 칸) 세로줄이다
                if m.end() - m.start() >= 2 * END_RUN or touches(x, m.start()) or touches(x, m.end() - 1):
                    vx.append(x)
                    break
        xs = cluster(vx)
        self.xs = sorted(set([0] + [x for x in xs if 0 < x < w - 1] + [w - 1]))
        # 선 위치 ±2px 안에 어두운 점이 있는지(선 두께와 앤티에일리어싱 흡수), 누적합으로 빠르게 센다
        self.vcov = {}
        self.vends_long = {}
        self.vends = {}   # 세로줄이 끝나는 y: 쪽이 끊기며 윗변·아랫변이 안 그려진 칸을 닫는 데 쓴다
        for x in self.xs:
            flags = [0] * h
            for xx in range(max(0, x - 2), min(w, x + 3)):
                c = mask[xx::w]
                flags = [a | b for a, b in zip(flags, c)]
            self.vcov[x] = _prefix(flags)
            ends, longs = [], []
            for m in re.finditer(rb"\x01{%d,}" % V_MIN, bytes(flags)):
                ends += [m.start(), m.end() - 1]
                if m.end() - m.start() >= END_RUN:
                    longs += [m.start(), m.end() - 1]
            self.vends[x] = ends
            self.vends_long[x] = longs
        # 세로줄 끝도 가로 경계 후보에 넣는다: 쪽이 끊긴 곳은 가로줄 없이 세로줄만 끝난다(첨부5 2쪽 윗변 등)
        ys = cluster([y for y, _, _ in hsegs] + [e for x in xs for e in self.vends_long[x]])
        self.ys = sorted(set([0] + [y for y in ys if 0 < y < h - 1] + [h - 1]))
        self.hcov = {}
        for y in self.ys:
            flags = [0] * w
            for yy in range(max(0, y - 2), min(h, y + 3)):
                r = mask[yy * w:(yy + 1) * w]
                flags = [a | b for a, b in zip(flags, r)]
            self.hcov[y] = _prefix(flags)
        self._build_cells()
        self._place_words(words(pdf, page))

    def _vline(self, x, ya, yb):
        a, b = ya + 3, yb - 2
        if b <= a:
            return True
        p = self.vcov[x]
        return (p[b] - p[a]) >= COVER * (b - a) and _ends_dark(p, ya, yb)

    def _hline(self, y, xa, xb):
        a, b = xa + 3, xb - 2
        if b <= a:
            return True
        p = self.hcov[y]
        if (p[b] - p[a]) >= COVER * (b - a) and _ends_dark(p, xa, xb):
            return True
        # 양옆 세로줄이 둘 다 여기서 끝나면 선이 있는 것으로 본다(쪽 경계에서 열린 칸)
        near = lambda x: any(abs(e - y) <= TOUCH + 2 for e in self.vends.get(x, ()))  # noqa: E731
        return near(xa) and near(xb)

    def _build_cells(self):
        xs, ys = self.xs, self.ys
        nx, ny = len(xs) - 1, len(ys) - 1
        parent = list(range(nx * ny))

        def find(a):
            while parent[a] != a:
                parent[a] = parent[parent[a]]
                a = parent[a]
            return a

        def union(a, b):
            ra, rb = find(a), find(b)
            if ra != rb:
                parent[max(ra, rb)] = min(ra, rb)

        idx = lambda i, j: j * nx + i  # noqa: E731
        for j in range(ny):
            for i in range(nx):
                if i + 1 < nx and not self._vline(xs[i + 1], ys[j], ys[j + 1]):
                    union(idx(i, j), idx(i + 1, j))
                if j + 1 < ny and not self._hline(ys[j + 1], xs[i], xs[i + 1]):
                    union(idx(i, j), idx(i, j + 1))
        roots = {}
        for j in range(ny):
            for i in range(nx):
                roots.setdefault(find(idx(i, j)), []).append((i, j))
        outside = set()
        for r, members in roots.items():
            if any(i == 0 or j == 0 or i == nx - 1 or j == ny - 1 for i, j in members):
                outside.add(r)
        # 칸 번호는 (위, 왼쪽) 순서로 다시 매긴다 → 같은 입력이면 같은 번호
        inside = sorted((r for r in roots if r not in outside),
                        key=lambda r: (min(j for _, j in roots[r]), min(i for i, _ in roots[r])))
        cid = {r: n for n, r in enumerate(inside)}
        self.grid = [[None] * ny for _ in range(nx)]
        self.cells = []
        for r in inside:
            m = roots[r]
            i0, i1 = min(i for i, _ in m), max(i for i, _ in m)
            j0, j1 = min(j for _, j in m), max(j for _, j in m)
            self.cells.append({"id": cid[r], "i0": i0, "i1": i1, "j0": j0, "j1": j1, "words": [],
                               "rect": len(m) == (i1 - i0 + 1) * (j1 - j0 + 1)})
            for i, j in m:
                self.grid[i][j] = cid[r]
        # 표: 맞닿은 칸 묶음
        tparent = list(range(len(self.cells)))

        def tfind(a):
            while tparent[a] != a:
                tparent[a] = tparent[tparent[a]]
                a = tparent[a]
            return a
        for i in range(nx):
            for j in range(ny):
                c = self.grid[i][j]
                if c is None:
                    continue
                for di, dj in ((1, 0), (0, 1)):
                    if i + di < nx and j + dj < ny:
                        d = self.grid[i + di][j + dj]
                        if d is not None and d != c:
                            ra, rb = tfind(c), tfind(d)
                            if ra != rb:
                                tparent[max(ra, rb)] = min(ra, rb)
        groups = {}
        for c in self.cells:
            groups.setdefault(tfind(c["id"]), []).append(c["id"])
        self.tables = [sorted(g) for _, g in sorted(groups.items())]

    def _place_words(self, ws):
        self.outside_words = []

        def cell_of(w):
            i = bisect.bisect_right(self.xs, w["cx"]) - 1
            j = bisect.bisect_right(self.ys, w["cy"]) - 1
            if 0 <= i < len(self.xs) - 1 and 0 <= j < len(self.ys) - 1:
                return self.grid[i][j]
            return None

        for w in ws:
            c = cell_of(w)
            if BULLET_ONLY.match(w["t"]):
                # 글머리표만 있는 낱말은 같은 줄 바로 오른쪽 낱말의 칸으로 보낸다. 내어쓰기 글머리표가 칸 왼쪽 선을
                # 넘어 옆 칸에 놓이는 경우가 있다(첨부6 '이니셜(initial) •', 'ASUM •': 글자층에만 있고 그려지지 않음)
                right = [v for v in ws if v is not w and abs(v["y0"] - w["y0"]) <= 3
                         and w["x1"] - 0.5 <= v["x0"] <= w["x1"] + 16]   # 글머리표와 글 사이(첨부6: 13pt)
                if right:
                    c = cell_of(min(right, key=lambda v: v["x0"]))
            if c is None:
                self.outside_words.append(w)
            else:
                self.cells[c]["words"].append(w)
        for c in self.cells:
            c["text"] = join_words(c["words"])

    def outside_lines(self):
        """표 밖 글을 줄 단위로: [(y_pt, text)]"""
        ws = sorted(self.outside_words, key=lambda w: (w["y0"], w["x0"]))
        lines, cur, cy = [], [], None
        for w in ws:
            if cy is None or abs(w["y0"] - cy) > 3:
                if cur:
                    lines.append((round(cy, 1), " ".join(x["t"] for x in sorted(cur, key=lambda z: z["x0"]))))
                cur, cy = [w], w["y0"]
            else:
                cur.append(w)
        if cur:
            lines.append((round(cy, 1), " ".join(x["t"] for x in sorted(cur, key=lambda z: z["x0"]))))
        return lines


def nospace(s):
    return re.sub(r"\s+", "", s)


_CONT_END = re.compile(r"(?:[,，·ㆍ∙/(\-–&]|및|또는|그리고)$")


# 칸 글이 앞 글에 이어지는 말로 시작하는가: 조사·연결어·닫는 괄호로 시작하는 글은 새 칸의 첫머리가 될 수 없다
_CONT_START = re.compile(r"^(?:단[,，\s]|및\s|또는\s|등\s|등의\s|[)\]」』]|[을를이가은는의에와과로도만](?:\s|$))")


def continues(text):
    return bool(_CONT_START.match(text.strip()))


def unfinished(text):
    """칸 글이 끝나지 않은 채 쪽이 넘어갔는가: 마지막 줄이 쉼표·가운뎃점·여는 괄호·연결어로 끝나거나,
    마지막 줄에서 연 괄호를 닫지 않았다. (칸 전체 괄호 수는 원문부터 안 맞는 경우가 있어 보지 않는다)"""
    t = text.strip()
    if not t:
        return False
    last = t.splitlines()[-1]
    if last.rfind("(") > last.rfind(")"):
        return True
    # 회사 이름 앞머리만 남고 끝났다('바로코퍼레이션㈜, ㈜' → 다음 쪽 '경남은행'). '주식회사'는 '엠즈씨드 주식회사'처럼
    # 뒤에 붙는 경우가 많아 넣지 않는다
    if re.search(r"(?:^|[,\s])(?:㈜|\(주\)|\(재\)|\(사\)|\(유\))$", t):
        return True
    return bool(_CONT_END.search(t))


class Fragment:
    """한 쪽 안의 표 한 조각."""

    def __init__(self, pg, cell_ids):
        self.pg = pg
        cs = [pg.cells[c] for c in cell_ids]
        self.cells = cs
        self.i0, self.i1 = min(c["i0"] for c in cs), max(c["i1"] for c in cs)
        self.j0, self.j1 = min(c["j0"] for c in cs), max(c["j1"] for c in cs)
        self.top_pt = pg.ys[self.j0] / S
        self.bottom_pt = pg.ys[self.j1 + 1] / S
        # 이 조각이 쓰는 세로줄: 어떤 칸의 왼쪽·오른쪽 변
        self.xlines = sorted({pg.xs[c["i0"]] for c in cs} | {pg.xs[c["i1"] + 1] for c in cs})

    def head(self, start_j):
        """start_j 행부터 시작하는 맨 위 행 묶음: 그 행에 걸친 칸의 가장 낮은 아래 행까지."""
        top = [c for c in self.cells if c["j0"] <= start_j <= c["j1"]]
        hb = max(c["j1"] for c in top)
        region = [c for c in self.cells if c["j0"] >= start_j and c["j1"] <= hb]
        return hb, region


def _match(xa, xb):
    return abs(xa - xb) <= COL_TOL


class Table:
    """여러 쪽에 걸친 논리 표."""

    def __init__(self, frag, caption, overrides=None):
        self.overrides = overrides or {}
        pg = frag.pg
        hb, region = frag.head(frag.j0)
        leaves = sorted((c for c in region if c["j1"] == hb), key=lambda c: c["i0"])
        self.cols = []
        for c in leaves:
            xa, xb = pg.xs[c["i0"]], pg.xs[c["i1"] + 1]
            path = []
            for p in sorted(region, key=lambda c: c["j0"]):
                if p["i0"] <= c["i0"] and p["i1"] >= c["i1"]:
                    t = re.sub(r"\s+", " ", p["text"]).strip()
                    if t and t not in path:
                        path.append(t)
            self.cols.append({"x": (xa, xb), "name": " > ".join(path)})
        self.left, self.right = self.cols[0]["x"][0], self.cols[-1]["x"][1]
        # 머리 칸마다 덮는 잎 열 범위 → 여러 열에 걸친 본문 칸의 이름(예: '위탁 업무 내용')
        self.groups = {}
        for p in region:
            xa, xb = pg.xs[p["i0"]], pg.xs[p["i1"] + 1]
            ks = [k for k, c in enumerate(self.cols) if c["x"][0] >= xa - COL_TOL and c["x"][1] <= xb + COL_TOL]
            t = re.sub(r"\s+", " ", p["text"]).strip()
            if len(ks) > 1 and t:
                self.groups.setdefault((ks[0], ks[-1]), t)
        # 다음 쪽 조각이 같은 표인지 볼 때 쓰는 세로줄: 맨 윗줄 머리 칸의 경계(잎 경계는 쪽마다 없을 수 있다)
        top = [p for p in region if p["j0"] == frag.j0]
        self.top_lines = sorted({pg.xs[p["i0"]] for p in top} | {pg.xs[p["i1"] + 1] for p in top})
        self.header_key = [nospace(c["text"]) for c in region]
        self.caption = caption
        self.first_page = pg.page
        self.header_rows = hb - frag.j0 + 1
        self.spans = []          # 칸 덩어리: {"texts": [], "pages": []}
        self.rows = []           # {"key": tuple, "pages": []}
        self.last_spans = None   # 마지막 행의 열별 칸 묶음
        self.header_repeats = []
        self.unmapped = []
        self.boundaries = []     # 쪽 경계마다 열별 결정: '=' 빈 칸 이음, '+' 글 이음, 'N' 새 칸, '?' 판단 못 함
        self._add(frag, hb + 1, continuation=False)

    def matches(self, frag):
        """쪽 첫 조각이 열린 표의 이어짐인가: 왼쪽·오른쪽 테두리가 같으면 이어짐으로 본다.
        안쪽 세로줄은 쪽마다 몇십 px 움직인다(첨부5 28쪽 1352px → 29쪽 1319px)."""
        pg = frag.pg
        return _match(pg.xs[frag.i0], self.left) and _match(pg.xs[frag.i1 + 1], self.right)

    def cover(self, pg, c):
        """칸이 덮는 논리 열: 겹친 길이가 칸 너비나 열 너비의 절반 이상인 열."""
        a, b = pg.xs[c["i0"]], pg.xs[c["i1"] + 1]
        ks = []
        for k, col in enumerate(self.cols):
            ov = min(b, col["x"][1]) - max(a, col["x"][0])
            if ov > 0 and ov >= 0.5 * min(b - a, col["x"][1] - col["x"][0]):
                ks.append(k)
        if not ks:  # 아주 가는 칸: 가장 많이 겹친 열
            best = max(range(len(self.cols)), key=lambda k: min(b, self.cols[k]["x"][1]) - max(a, self.cols[k]["x"][0]))
            ks = [best]
        return ks

    def add_continuation(self, frag):
        start = frag.j0
        hb, region = frag.head(start)
        if [nospace(c["text"]) for c in region] == self.header_key:
            self.header_repeats.append(frag.pg.page)
            start = hb + 1
        self._add(frag, start, continuation=True)

    def span_text(self, sid):
        return "\n".join(self.spans[sid]["texts"])

    def _add(self, frag, start_j, continuation):
        pg = frag.pg
        self._add_rows(frag, start_j, continuation)
        # 행에 안 들어간 칸(머리 행 제외)이 있으면 남긴다: 조용히 버리지 않는다
        for c in frag.cells:
            if c["j0"] >= start_j and c["id"] not in self._last_span_of and c["text"].strip():
                self.unmapped.append({"page": pg.page, "text": c["text"]})

    def _add_rows(self, frag, start_j, continuation):
        pg = frag.pg
        covers = {c["id"]: self.cover(pg, c) for c in frag.cells}
        span_of = self._last_span_of = {}
        first = True
        for j in range(start_j, frag.j1 + 1):
            per_col = [[] for _ in self.cols]
            for c in sorted((c for c in frag.cells if c["j0"] <= j <= c["j1"]), key=lambda c: c["i0"]):
                for k in covers[c["id"]]:
                    if c["id"] not in per_col[k]:
                        per_col[k].append(c["id"])
            if not any(per_col):
                continue
            if first:
                first = False
                if continuation and self.last_spans is not None:
                    self._join_page_top(pg, per_col, span_of)
            key = []
            for cs in per_col:
                ids = []
                for c in cs:
                    if c not in span_of:
                        t = pg.cells[c]["text"]
                        self.spans.append({"texts": [t] if t.strip() else [], "pages": [pg.page]})
                        span_of[c] = len(self.spans) - 1
                    if span_of[c] not in ids:
                        ids.append(span_of[c])
                key.append(tuple(ids))
            key = tuple(key)
            y0, y1 = round(pg.ys[j] / S, 1), round(pg.ys[j + 1] / S, 1)
            if self.rows and self.rows[-1]["key"] == key:
                if pg.page not in self.rows[-1]["pages"]:
                    self.rows[-1]["pages"].append(pg.page)
                box = self.rows[-1]["ybox"].setdefault(str(pg.page), [y0, y1])
                box[0], box[1] = min(box[0], y0), max(box[1], y1)
            else:
                self.rows.append({"key": key, "pages": [pg.page], "ybox": {str(pg.page): [y0, y1]}})
            self.last_spans = key

    def _join_page_top(self, pg, per_col, span_of):
        """쪽 맨 위 행의 칸을 앞 쪽 마지막 행의 칸에 이을지 정한다.
        - 빈 칸: 앞 칸이 이어진 것(세로로 합친 칸이거나 글이 앞 쪽에서 끝난 칸)
        - 글이 있는 칸: 앞 칸 글이 끝나지 않았으면(쉼표·가운뎃점·여는 괄호·연결어로 끝남) 이어진 것
        - 한 행에만 걸친 앞 칸이 이렇게 이어지면 그 행이 쪽에서 잘린 것이므로, 글이 있는 다른 칸도 모두 잇는다
        - 그 밖에는 새 칸. 빈 칸('=')과 새 칸('N')이 섞이면 '애매'로 남긴다(행이 잘린 것인지 새 행인지 글만으로 모른다)
        실험 03은 '둘째 열이 비면 행 전체가 이어진 것'으로 봤는데, 둘째 열이 여러 행을 합친 칸(첨부1 법적 근거,
        첨부4 위탁 협력 회사)이면 새 행을 앞 행에 붙이는 잘못이 생겨 쓰지 않는다."""
        prev_row = self.rows[-2]["key"] if len(self.rows) >= 2 else None

        def single(k):
            return len(per_col[k]) == 1 and len(self.last_spans[k]) == 1

        def text(k):
            return pg.cells[per_col[k][0]]["text"].strip()

        def only_last(k):
            return prev_row is None or self.last_spans[k][0] not in prev_row[k]

        def evident(k):
            return only_last(k) and text(k) and (unfinished(self.span_text(self.last_spans[k][0]))
                                                 or continues(text(k)))

        # 행이 잘렸다는 증거: (1) 한 행짜리 앞 칸의 글이 끝나지 않았는데 이 쪽에 글이 있다
        #                      (2) 한 행짜리 앞 칸 자리가 이 쪽에서 비어 있다(새 행이면 그 열에 새 글이 있어야 한다)
        cut_text = [k for k in range(len(per_col)) if single(k) and evident(k)]
        #   단 (2)의 빈 칸이 이 쪽에서 다른 열 첫 칸보다 아래로 길면 새로 시작한 '합친 칸'일 수 있어 증거로 안 쓴다
        #   (첨부1 20쪽: 앞 쪽 마지막 행에서 시작한 서비스명 칸이 이 쪽 여러 행에 걸친다)
        def bottom(k):
            return pg.cells[per_col[k][0]]["j1"]

        def short(k):
            others = [bottom(m) for m in range(len(per_col)) if m != k and per_col[m]]
            return not others or bottom(k) <= min(others)
        #   첫 열(서비스명)은 여러 행을 묶는 열이라 증거로 안 쓴다(첨부1 20쪽 T 메모링프리미엄: 두 행짜리 서비스가
        #   쪽 경계에서 나뉘었다). 열이 둘뿐인 표(첨부6·8)는 첫 열밖에 증거가 없어 쓴다
        first = 0 if len(per_col) <= 2 else 1
        cut_empty = [k for k in range(first, len(per_col))
                     if single(k) and not text(k) and only_last(k) and short(k)]
        # 열 모양이 바뀌면(앞 행은 여러 열에 걸친 한 칸인데 이 쪽 첫 행은 열마다 칸이 따로 있거나 그 반대) 잘린 행이
        # 아니라 새 행이다(첨부4 20·27쪽: 위탁사 직접 업무 한 칸 → 다음 쪽 재위탁 행). 모양이 바뀐 열은 잇지 않는다
        changed = set()
        for k in range(len(per_col) - 1):
            if (self.last_spans[k] == self.last_spans[k + 1]) != (per_col[k] == per_col[k + 1]):
                changed |= {k, k + 1}
        if changed:
            cut_text = cut_empty = []
        row_cont = bool(cut_text or cut_empty)
        prev_unf = {k: unfinished(self.span_text(self.last_spans[k][0])) for k in range(len(per_col)) if single(k)}
        # 사람이 그림을 보고 정한 쪽 경계(쪽경계_수정.json)가 있으면 그것을 따른다
        forced = self.overrides.get(pg.page)
        if forced == "new":
            row_cont, cut_text, cut_empty = False, [], []
            prev_unf = {k: False for k in prev_unf}
        elif forced == "join":
            row_cont = True
        decision = []
        for k, cs in enumerate(per_col):
            if not single(k):
                decision.append("?")
                continue
            if k in changed:
                decision.append("N")
                continue
            c = cs[0]
            sid = self.last_spans[k][0]
            t = text(k)
            if not t or row_cont or prev_unf[k]:
                span_of[c] = sid
                if t:
                    self.spans[sid]["texts"].append(pg.cells[c]["text"])
                if pg.page not in self.spans[sid]["pages"]:
                    self.spans[sid]["pages"].append(pg.page)
                decision.append("=" if not t else "+")
            else:
                decision.append("N")
        d = "".join(decision) + (f" 수정:{forced}" if forced else "")
        # 추정으로 이은 칸: 앞 글이 끝났는데 '행이 잘렸다'는 판단만으로 글을 이었다(빈 칸 증거만 있을 때)
        inferred = [k for k, x in enumerate(d) if x == "+" and not prev_unf[k]] if row_cont and not cut_text else []
        if changed:
            d += " 모양변경"
        self.boundaries.append({"page": pg.page, "row_cont": row_cont, "decision": d,
                                "evidence": "글 이어짐" if cut_text else ("빈 칸" if cut_empty else ""),
                                "inferred_cols": inferred, "row_index": len(self.rows),
                                "joined_spans": [self.last_spans[k][0] for k, x in enumerate(d) if x == "+"],
                                "inferred_spans": [self.last_spans[k][0] for k in inferred]})

    def records(self):
        out = []
        for r in self.rows:
            fields, merged, multi = [], [], []
            k = 0
            key = r["key"]
            while k < len(key):
                ids = key[k]
                k2 = k
                # 같은 칸 하나가 오른쪽 열까지 덮으면 한 칸으로 묶는다
                while len(ids) == 1 and k2 + 1 < len(key) and key[k2 + 1] == ids:
                    k2 += 1
                if k2 > k:
                    name = self.groups.get((k, k2)) or f"{self.cols[k]['name']}~{self.cols[k2]['name']}"
                    merged.append(name)
                else:
                    name = self.cols[k]["name"]
                if len(ids) > 1:
                    multi.append(name)
                parts = ["\n".join(self.spans[sid]["texts"]) for sid in ids]
                fields.append((name, "\n".join(p for p in parts if p)))
                k = k2 + 1
            out.append({"pages": r["pages"], "fields": fields, "merged": merged, "multi": multi,
                        "spans": [list(ids) for ids in key], "ybox": r["ybox"]})
        return out


def extract(pdf, first=None, last=None, overrides=None, lines_add=None):
    """PDF 하나 → {"pages", "tables": [{caption, columns, first_page, rows, header_repeats}], "outside": [...]}"""
    n = page_count(pdf)
    first, last = first or 1, last or n
    tables, outside, page_stats = [], [], []
    open_t = None
    with tempfile.TemporaryDirectory() as tmp:
        for p in range(first, last + 1):
            pg = Page(pdf, p, tmp, (lines_add or {}).get(p, ()))
            frags = sorted((Fragment(pg, t) for t in pg.tables), key=lambda f: f.top_pt)
            lines = pg.outside_lines()
            used = set()
            for n_f, f in enumerate(frags):
                if n_f == 0 and open_t is not None and open_t.matches(f):
                    open_t.add_continuation(f)
                    continue
                # 표 바로 위(40pt 이내) 표 밖 줄을 표 제목으로 본다
                cap = [(y, t) for y, t in lines if f.top_pt - 40 <= y < f.top_pt and (y, t) not in used]
                caption = cap[-1][1] if cap else ""
                if cap:
                    used.add(cap[-1])
                open_t = Table(f, caption, overrides)
                tables.append(open_t)
            for y, t in lines:
                outside.append({"page": p, "y": y, "text": t, "caption": (y, t) in used})
            page_stats.append({"page": p, "tables": len(frags), "cells": len(pg.cells),
                               "words": sum(len(c["words"]) for c in pg.cells) + len(pg.outside_words),
                               "words_outside": len(pg.outside_words)})
    return {"pages": n, "tables": [{"caption": t.caption, "first_page": t.first_page,
                                    "columns": [c["name"] for c in t.cols], "rows": t.records(),
                                    "header_repeats": t.header_repeats, "boundaries": t.boundaries,
                                    "unmapped": t.unmapped, "header_rows": t.header_rows}
                                   for t in tables],
            "outside": outside, "page_stats": page_stats, "extractor": EXTRACTOR_VERSION}


if __name__ == "__main__":
    import json
    import sys
    pdf = sys.argv[1]
    a = [int(x) for x in sys.argv[2:4]] if len(sys.argv) > 2 else []
    res = extract(pdf, first=a[0] if a else None, last=a[1] if len(a) > 1 else None)
    print(json.dumps(res, ensure_ascii=False, indent=1))
