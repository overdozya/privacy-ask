"""처리 원장(D8) DB 만들기: SKT 처리방침 첨부 1~10 PDF → data/out/ledger/ledger.db

실행(build/에서): python3 ledger/build_ledger.py
- 입력: ref/개인정보처리방침_20261028/ 의 PDF 10개(읽기만 한다)
- 추출 결과는 data/out/ledger/cache/에 (파일 해시, 추출기 버전) 이름으로 둔다. 같은 PDF·같은 추출기면 다시 뽑지 않는다.
- 같은 입력이면 바이트까지 같은 DB가 나온다(시각·임의 값을 넣지 않고, 넣는 순서를 고정한다).
- 자동 점검 결과는 data/out/ledger/점검/자동점검.json 과 자동점검.md 로 쓴다.
"""
import hashlib
import json
import re
import sqlite3
import sys
import unicodedata
from collections import Counter, defaultdict
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))  # build/: paths.py
import paths as P  # noqa: E402
sys.path.insert(0, str(HERE))
import extract as ex  # noqa: E402
import tags  # noqa: E402

SRC = P.POLICY_PDFS
DB = P.LEDGER_DB
CACHE = P.LEDGER_CACHE
CHECK_DIR = P.LEDGER_CHECK
VERSION_ID = "20261028"
PUBLISHED_ON = "2026-10-28"


# ---------------------------------------------------------------- 입력 찾기
def find_pdfs(src=SRC):
    """{1: Path, …, 10: Path}. 맥 파일 이름은 NFD라 NFC로 바꿔 번호를 읽는다."""
    out = {}
    for p in sorted(src.glob("*.pdf")):
        m = re.match(r"첨부(\d+)", unicodedata.normalize("NFC", p.name))
        if m:
            out[int(m.group(1))] = p
    return out


OVERRIDES = P.LEDGER_OVERRIDES


def load_overrides(path=OVERRIDES):
    """작업 세션·사람이 그림을 보고 정한 수정.
    쪽 경계 판단 {첨부 번호: {쪽: 'new'|'join'}}, 빠진 칸 경계선 {첨부 번호: {쪽: [(y, x0, x1) pt]}}"""
    out, lines = defaultdict(dict), defaultdict(lambda: defaultdict(list))
    if path.exists():
        data = json.loads(path.read_text(encoding="utf-8"))
        for e in data["수정"]:
            out[int(e["첨부"].removeprefix("첨부"))][int(e["쪽"])] = e["판단"]
        for e in data.get("선 추가", []):
            lines[int(e["첨부"].removeprefix("첨부"))][int(e["쪽"])].append(tuple(e["y_x0_x1_pt"]))
    return out, lines


def extract_cached(n, pdf, cache=CACHE, use_cache=True, overrides=None, lines_add=None):
    sha = ex.file_sha256(pdf)
    ov, la = overrides or {}, {k: v for k, v in (lines_add or {}).items()}
    tag = hashlib.sha256(json.dumps([sorted(ov.items()), sorted(la.items())]).encode()).hexdigest()[:8]
    f = cache / f"첨부{n}.{sha[:16]}.{ex.EXTRACTOR_VERSION}.{tag}.json"
    if use_cache and f.exists():
        return sha, json.loads(f.read_text(encoding="utf-8"))
    res = ex.extract(pdf, overrides=ov, lines_add=la)
    if use_cache:
        cache.mkdir(parents=True, exist_ok=True)
        f.write_text(json.dumps(res, ensure_ascii=False, indent=0), encoding="utf-8")
    return sha, res


# ---------------------------------------------------------------- 칸 종류
KIND_RULES = [  # 열 이름(두 줄 머리면 마지막 이름)에 들어 있는 말 → 종류. 위에서부터 먼저 맞는 것
    ("서비스", "서비스"), ("법적 근거", "법적 근거"), ("보유", "보유기간"), ("기간", "보유기간"),
    ("항목", "항목"), ("목적", "목적"), ("국가", "국가"), ("업무", "업무"),
    ("받는 자", "받는 자"), ("이전받는", "받는 자"), ("협력 회사", "받는 자"), ("제공자", "제공자"),
    ("고려사항", "고려사항"), ("거부", "거부방법"), ("담당자", "담당자"), ("내용", "내용"),
    ("구분", "목적 분류"),   # 첨부9 '구분'(통계작성·과학적 연구)
]


def col_kind(name, merged=False):
    if merged and "~" in name:
        return "여러 열"
    leaf = name.split(" > ")[-1]
    for key, kind in KIND_RULES:
        if key in leaf:
            return kind
    return "기타"


# ---------------------------------------------------------------- 조각 나누기(실험 07 N1·N2·N3)
PUA = re.compile(r"[-]")
BULLET = re.compile(r"^(?:[-∙·ㆍ•●○▪■]\s|-(?=\S)|\d{1,2}[.)]\s?|\(\d{1,2}\)\s?|[①-⑳]\s?|[가나다라마바사아자차카타파하][.)]\s)")
NOTE = re.compile(r"^※")
STAR_LINE = re.compile(r"^(\*{1,2})(?!\*)")
# 각주 참조 '용어*': 별표 앞은 글자(공백·별표·여는 괄호 아님), 뒤는 글자가 아니다.
# '(*유튜브 …)', '*Btv'처럼 별표 뒤에 바로 글이 오는 것은 글 안의 표시라 참조로 보지 않는다
STAR_REF = re.compile(r"(?<=[^\s*(])(\*{1,2})(?![\*\w])")


def flat(s):
    return re.sub(r"\s+", " ", PUA.sub(" ", s)).strip()


def nospace(s):
    return re.sub(r"\s+", "", PUA.sub("", s))


def split_cell(text):
    """칸 글을 줄 앞 기호로 나눈다. 기호 없는 줄은 앞 조각에 잇는다."""
    segs = []
    for line in text.split("\n"):
        s = line.strip()
        if not s:
            continue
        if NOTE.match(s):
            kind = "NOTE"
        elif STAR_LINE.match(s):
            kind = "FOOTNOTE"
        elif BULLET.match(s):
            kind = "BULLET"
        else:
            kind = None
        if kind or not segs:
            segs.append({"type": kind or "LEAD", "raw": [line]})
        else:
            segs[-1]["raw"].append(line)
    for s in segs:
        s["text"] = flat("\n".join(s["raw"]))
        del s["raw"]
    return segs


def footnote_term(seg_text, fn_text, stars):
    """조각 안 '용어*'의 용어가 각주 '* 용어…' 첫머리와 맞으면 그 용어(띄어쓰기 없앤 것)."""
    body = nospace(fn_text.lstrip("*"))
    best = None
    for m in re.finditer(r"(?<=[^\s*(])" + re.escape(stars) + r"(?![\*\w])", seg_text):
        words = re.split(r"\s+", seg_text[:m.start()].rstrip())
        for k in range(1, 6):
            term = nospace(" ".join(words[-k:]).lstrip("-∙·ㆍ•●○▪■( "))
            if len(term) >= 2 and body.startswith(term) and (best is None or len(term) > len(best)):
                best = term
    return best


def segments_for_row(row_id, fields, kinds):
    """조각 목록과 펼치지 못한 각주 참조 목록을 돌려준다."""
    segs = []
    for k, ((name, text), (_, kind)) in enumerate(zip(fields, kinds)):
        for n, p in enumerate(split_cell(text), 1):
            segs.append({"segment_id": f"{row_id}#c{k}.{n}", "col_name": name, "col_kind": kind,
                         "seg_type": p["type"], "text": p["text"]})
    defs = [s for s in segs if s["seg_type"] == "FOOTNOTE"]
    refs = []
    for s in segs:
        if s["seg_type"] == "FOOTNOTE":
            continue
        for m in STAR_REF.finditer(s["text"]):
            refs.append((s, m.group(1)))
    children, used, unresolved = [], set(), []
    for s, stars in refs:
        cands = [d for d in defs if STAR_LINE.match(d["text"]).group(1) == stars]
        hit = [(footnote_term(s["text"], d["text"], stars), d) for d in cands]
        hit = [(t, d) for t, d in hit if t]
        if not hit and len(cands) == 1 and sum(1 for _, st in refs if st == stars) == 1:
            hit = [(None, cands[0])]   # 행 안에 같은 별표 참조·정의가 하나씩이면 짝으로 본다
        if not hit:
            unresolved.append((s["segment_id"], stars, s["text"]))
            continue
        for i, (term, d) in enumerate(hit, 1):
            used.add(d["segment_id"])
            children.append({"segment_id": f"{s['segment_id']}.F{i}", "col_name": d["col_name"],
                             "col_kind": s["col_kind"], "seg_type": "FOOTNOTE_DEF", "parent_id": s["segment_id"],
                             "term": term, "source_id": d["segment_id"],
                             "text": flat(d["text"].lstrip("*"))})
    # 펼친 각주는 참조 조각 바로 뒤에 둔다
    out = []
    for s in segs:
        out.append(s)
        out += [c for c in children if c["parent_id"] == s["segment_id"]]
    for i, s in enumerate(out, 1):
        s["seq"] = i
    return out, unresolved, [d for d in defs if d["segment_id"] not in used]


# ---------------------------------------------------------------- 원장 만들기
def build_records(results):
    """results: {n: (sha, pdf, extract_result)} → versions, rows, segments, unextracted, 점검 재료"""
    versions, rows, segs, unext = [], [], [], []
    audit = {}
    for n in sorted(results):
        sha, pdf, res = results[n]
        att = f"첨부{n}"
        page1 = sorted((o for o in res["outside"] if o["page"] == 1), key=lambda o: o["y"])
        title = page1[0]["text"] if page1 else ""
        versions.append((VERSION_ID, PUBLISHED_ON, att, title, unicodedata.normalize("NFC", pdf.name), sha,
                         res["pages"], res["extractor"]))
        seq = 0
        a = {"rows": 0, "tables": len(res["tables"]), "pages": res["pages"], "row_ids": []}
        for o in res["outside"]:
            if o["caption"] or (o["page"] == 1 and o["text"] == title):
                continue
            unext.append((att, o["page"], "OUTSIDE_TEXT", "", o["text"]))
        for t_no, t in enumerate(res["tables"], 1):
            joined = defaultdict(list)
            for b in t["boundaries"]:
                for sid in b["joined_spans"]:
                    joined[sid].append(("추정" if sid in b["inferred_spans"] else "글") + f"@{b['page']}")
            for u in t["unmapped"]:
                unext.append((att, u["page"], "UNMAPPED_CELL", "", u["text"]))
            for r in t["rows"]:
                seq += 1
                rid = f"{att}-{seq:04d}"
                fields = [(k, v) for k, v in r["fields"]]
                kinds = [(k, col_kind(k, k in r["merged"])) for k, _ in fields]
                service = flat(next((v for (k, v), (_, kd) in zip(fields, kinds) if kd == "서비스"), ""))
                flags = {}
                js = sorted({x for ids in r["spans"] for sid in ids for x in joined.get(sid, [])})
                if js:
                    flags["page_join"] = js
                if r["multi"]:
                    flags["multi_cell"] = r["multi"]
                if r.get("ybox"):
                    flags["ybox"] = r["ybox"]   # 쪽별 행 위·아래 위치(pt, 쪽 높이 595): 사람이 PDF에서 찾기 쉽게
                rows.append({"row_id": rid, "attachment": att, "seq": seq, "table_no": t_no,
                             "table_title": t["caption"], "service": service, "fields": fields, "kinds": kinds,
                             "pages": r["pages"], "merged": r["merged"], "flags": flags,
                             "n_cols": len(t["columns"])})
                ss, unresolved, loose = segments_for_row(rid, fields, kinds)
                for s in ss:
                    s["row_id"] = rid
                segs += ss
                for sid, stars, text in unresolved:
                    unext.append((att, r["pages"][0], "FOOTNOTE_UNRESOLVED", sid, f"참조 '{stars}' 정의 없음: {text}"))
                rows[-1]["unresolved_refs"] = [u[0] for u in unresolved]
                rows[-1]["loose_defs"] = [d["segment_id"] for d in loose]
                a["rows"] += 1
        audit[att] = a
    return versions, rows, segs, unext, audit


def write_db(path, versions, rows, segs, unext):
    if path.exists():
        path.unlink()
    path.parent.mkdir(parents=True, exist_ok=True)
    con = sqlite3.connect(path)
    con.executescript((HERE / "schema.sql").read_text(encoding="utf-8"))
    j = lambda x: json.dumps(x, ensure_ascii=False)  # noqa: E731
    con.executemany("INSERT INTO ledger_versions VALUES (?,?,?,?,?,?,?,?)", versions)
    con.executemany("INSERT INTO ledger_rows VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)", [
        (r["row_id"], VERSION_ID, r["attachment"], r["seq"], r["table_no"], r["table_title"], r["service"],
         nospace(r["service"]),
         j([list(f) for f in r["fields"]]), j([list(k) for k in r["kinds"]]), j(r["pages"]), r["pages"][0],
         j(r["merged"]), j(r["flags"])) for r in rows])
    con.executemany("INSERT INTO ledger_segments VALUES (?,?,?,?,?,?,?,?,?,?)", [
        (s["segment_id"], s["row_id"], s["col_name"], s["col_kind"], s["seg_type"], s["seq"],
         s.get("parent_id"), s.get("term"), s.get("source_id"), s["text"]) for s in segs])
    con.executemany("INSERT INTO ledger_unextracted VALUES (?,?,?,?,?)", unext)
    con.commit()
    con.execute("VACUUM")
    con.close()


# ---------------------------------------------------------------- 자동 점검
STAR_ANY = re.compile(r"\*")


def checks(results, rows, segs, unext):
    """첨부별: 쪽, 행, 빈 칸, 칸 수가 다른 행, 각주 표시가 남은 칸, ID 중복, 쪽 경계 이음. 이상 행 목록도 낸다."""
    ids = Counter(r["row_id"] for r in rows)
    segs_resolved = defaultdict(list)   # 참조 조각 → 펼친 각주
    for s in segs:
        if s["seg_type"] == "FOOTNOTE_DEF":
            segs_resolved[s["parent_id"]].append(s["segment_id"])
    out, anomalies = {}, []
    for n in sorted(results):
        att = f"첨부{n}"
        _, _, res = results[n]
        rs = [r for r in rows if r["attachment"] == att]
        empty = [(r["row_id"], k) for r in rs for k, v in r["fields"] if not v.strip()]
        # 칸 수가 다른 행: 머리 행에 이름이 있는 묶음 칸(첨부4 '위탁 업무 내용' 한 칸)은 표 구조라 따로 센다
        diffcols = [r["row_id"] for r in rs if len(r["fields"]) != r["n_cols"] and any("~" in m for m in r["merged"])]
        grouped = [r["row_id"] for r in rs if len(r["fields"]) != r["n_cols"] and not any("~" in m for m in r["merged"])]
        # 각주 표시가 남은 칸: 줄 앞 '*'(각주 정의·별표 글머리)와 짝을 찾은 참조 말고 '*'가 남은 칸
        left_cells = set()
        for s in segs:
            if not s["row_id"].startswith(att + "-") or s["seg_type"] == "FOOTNOTE_DEF":
                continue
            t = STAR_LINE.sub("", s["text"]) if s["seg_type"] == "FOOTNOTE" else s["text"]
            n_star = len(re.findall(r"\*+", t))
            ok = sum(1 for x in segs_resolved.get(s["segment_id"], ()))
            if n_star > ok:
                left_cells.add((s["row_id"], s["col_name"]))
        star_cells = [(r["row_id"], k) for r in rs for k, v in r["fields"] if STAR_ANY.search(v)]
        unresolved = [(r["row_id"], x) for r in rs for x in r["unresolved_refs"]]
        expanded = [s for s in segs if s["row_id"].startswith(att + "-") and s["seg_type"] == "FOOTNOTE_DEF"]
        bnds = [b for t in res["tables"] for b in t["boundaries"]]
        # PDF 태그(Word가 넣은 표 행 TR) 대조: 쪽마다 본문 TR 수 = 그 쪽에 걸친 우리 행 수 여야 한다.
        # Word는 쪽에서 잘린 행도 쪽마다 TR을 따로 만들므로, 쪽 안 행 나누기를 그림과 독립으로 확인한다.
        pdf = results[n][1]
        ttabs = tags.tables(pdf)
        tag_pp, our_pp = Counter(), Counter()
        for t, tt in zip(res["tables"], ttabs):
            for tr in tt["rows"][t["header_rows"]:]:
                for p in tr["pages"]:
                    tag_pp[p] += 1
        for r in rs:
            for p in r["pages"]:
                our_pp[p] += 1
        tag_bad = sorted(p for p in set(tag_pp) | set(our_pp) if tag_pp[p] != our_pp[p])
        tag_body = sum(len(tt["rows"]) - t["header_rows"] for t, tt in zip(res["tables"], ttabs))
        joins = sum(1 for b in bnds if b["row_cont"])
        out[att] = {
            "쪽": res["pages"], "표": len(res["tables"]), "행": len(rs),
            "태그 표 수": len(ttabs), "태그 본문 TR": tag_body, "쪽 경계 이음": joins,
            "TR=행+이음": tag_body == len(rs) + joins, "태그와 행 수 다른 쪽": tag_bad,
            "단어(표 안/전체)": f"{sum(p['words'] - p['words_outside'] for p in res['page_stats'])}/"
                             f"{sum(p['words'] for p in res['page_stats'])}",
            "표 없는 쪽": [p["page"] for p in res["page_stats"] if p["tables"] == 0],
            "빈 칸": len(empty), "칸 수가 다른 행": len(diffcols), "머리 묶음 칸 행": len(grouped),
            "별표(*)가 있는 칸": len(star_cells), "펼친 각주": len(expanded), "짝 없는 각주 참조": len(unresolved),
            "각주 표시가 남은 칸": len(left_cells),
            "행 ID 중복": sum(1 for r in rs if ids[r["row_id"]] > 1),
            "쪽 경계": len(bnds), "쪽 경계 글 이음(끝나지 않은 글)": sum(1 for b in bnds if b["evidence"] == "글 이어짐"),
            "쪽 경계 추정 이음(빈 칸 근거)": sum(1 for b in bnds if b["inferred_cols"]),
            "여러 칸이 한 열에 든 행": sum(1 for r in rs if "multi_cell" in r["flags"]),
            "여러 열에 걸친 칸이 있는 행": sum(1 for r in rs if r["merged"]),
            "표 밖 글 줄": sum(1 for u in unext if u[0] == att and u[2] == "OUTSIDE_TEXT"),
            "행에 안 들어간 칸": sum(1 for u in unext if u[0] == att and u[2] == "UNMAPPED_CELL"),
        }
        for rid, k in empty:
            anomalies.append({"row_id": rid, "kind": "빈 칸", "detail": k})
        for rid in diffcols:
            r = next(x for x in rs if x["row_id"] == rid)
            anomalies.append({"row_id": rid, "kind": "칸 수가 다름",
                              "detail": f"{len(r['fields'])}칸/{r['n_cols']}열, 합친 칸 {r['merged']}"})
        for rid, col in sorted(left_cells):
            r = next(x for x in rs if x["row_id"] == rid)
            v = dict(r["fields"])[col]
            lines = [ln for ln in v.split("\n") if "*" in ln]
            anomalies.append({"row_id": rid, "kind": "각주 표시 남음", "detail": f"{col}: {lines}"})
        for r in rs:
            if "multi_cell" in r["flags"]:
                anomalies.append({"row_id": r["row_id"], "kind": "한 열에 칸 여럿", "detail": r["flags"]["multi_cell"]})
            pj = [x for x in r["flags"].get("page_join", []) if x.startswith("추정")]
            if pj:
                anomalies.append({"row_id": r["row_id"], "kind": "쪽 경계 추정 이음", "detail": pj})
        for b in bnds:
            if "?" in b["decision"]:
                anomalies.append({"row_id": f"{att} {b['page']}쪽 위", "kind": "쪽 경계 판단 못 함",
                                  "detail": b["decision"]})
    total = {k: sum(v[k] for v in out.values()) for k in out["첨부1"] if isinstance(out["첨부1"][k], int)}
    return {"첨부별": out, "합계": total, "행 ID 중복(전체)": sum(1 for v in ids.values() if v > 1),
            "이상 행": anomalies}


def write_checks(report):
    CHECK_DIR.mkdir(parents=True, exist_ok=True)
    (CHECK_DIR / "자동점검.json").write_text(json.dumps(report, ensure_ascii=False, indent=1), encoding="utf-8")
    cols = list(next(iter(report["첨부별"].values())).keys())
    lines = ["# 처리 원장 자동 점검", "", "build_ledger.py가 만든다. 숫자는 첨부별, 마지막 줄은 합계.", "",
             "| 첨부 | " + " | ".join(cols) + " |", "|---" * (len(cols) + 1) + "|"]
    for att, v in report["첨부별"].items():
        lines.append(f"| {att} | " + " | ".join(str(v[c]) for c in cols) + " |")
    lines.append("| 합계 | " + " | ".join(str(report["합계"].get(c, "")) for c in cols) + " |")
    lines += ["", f"행 ID 중복(전체): {report['행 ID 중복(전체)']}", "",
              f"## 이상 행 {len(report['이상 행'])}건", "", "| 행 | 종류 | 내용 |", "|---|---|---|"]
    for a in report["이상 행"]:
        lines.append(f"| {a['row_id']} | {a['kind']} | {json.dumps(a['detail'], ensure_ascii=False)[:160]} |")
    (CHECK_DIR / "자동점검.md").write_text("\n".join(lines) + "\n", encoding="utf-8")


def main(use_cache=True):
    pdfs = find_pdfs()
    missing = sorted(set(range(1, 11)) - set(pdfs))
    if missing:
        raise SystemExit(f"첨부 PDF가 없다: {missing}")
    results = {}
    ov, la = load_overrides()
    for n, pdf in pdfs.items():
        sha, res = extract_cached(n, pdf, use_cache=use_cache, overrides=ov.get(n), lines_add=la.get(n))
        results[n] = (sha, pdf, res)
    versions, rows, segs, unext, _ = build_records(results)
    write_db(DB, versions, rows, segs, unext)
    report = checks(results, rows, segs, unext)
    write_checks(report)
    print(json.dumps(report["합계"], ensure_ascii=False))
    print("rows", len(rows), "segments", len(segs), "unextracted", len(unext), "->", DB)


if __name__ == "__main__":
    main(use_cache="--no-cache" not in sys.argv)
