"""법제처 API 응답(JSON)을 조·항·호·목 구조로 읽는다.

- 법령(eflaw): API가 이미 조문단위 > 항 > 호 > 목으로 나눠 준다. 그대로 읽는다.
- 행정규칙(admrul): 조문 하나가 한 줄 글이다(항·호 경계에 줄바꿈 없음). 원숫자(①②)와
  "다음 번호" 규칙으로 나눈다. 다음 번호만 찾으므로 본문 속 "제2조"의 "2." 같은 오인식이 적다.

여기서는 글을 고치지 않는다. 줄 사이에 "\n"만 넣는다(확정 묶음 1단계와 같은 모양).
"""
from __future__ import annotations

import re
from dataclasses import dataclass, field

CIRCLED = "①②③④⑤⑥⑦⑧⑨⑩⑪⑫⑬⑭⑮⑯⑰⑱⑲⑳㉑㉒㉓㉔㉕㉖㉗㉘㉙㉚㉛㉜㉝㉞㉟㊱㊲㊳㊴㊵㊶㊷㊸㊹㊺㊻㊼㊽㊾㊿"
MOK_ORDER = "가나다라마바사아자차카타파하"
DELETED = re.compile(r"^\s*(?:제\d+조(?:의\d+)?\s*)?(?:(?:[①-㊿]|<\d+>)\s*)?(?:\d+(?:의\d+)?\.\s*)?(?:[가-하]\.\s*)?삭제\s*(?:<([^>]*)>)?\s*$")


def hang_no(raw: str) -> int:
    """① → 1, <16> → 16 (API는 ⑮ 다음 항을 <16>처럼 준다)."""
    raw = raw.strip()
    m = re.match(r"<(\d+)>", raw)
    if m:
        return int(m.group(1))
    return CIRCLED.index(raw[0]) + 1


def as_list(x):
    if x is None:
        return []
    return x if isinstance(x, list) else [x]


def flat_text(x):
    """API의 내용 칸은 문자열이거나 문자열 리스트(줄)이다.
    줄마다 앞뒤 공백(들여쓰기)만 없앤다. 글자는 고치지 않는다(확정 묶음 1단계와 같음)."""
    if x is None:
        return ""
    if isinstance(x, list):
        return "\n".join(flat_text(v) for v in x if flat_text(v) != "")
    return "\n".join(line.strip() for line in str(x).split("\n"))


@dataclass
class Mok:
    no: str          # "가"
    text: str


@dataclass
class Ho:
    no: int
    sub: int         # 1의2 → sub 2, 없으면 0
    text: str
    moks: list[Mok] = field(default_factory=list)

    @property
    def label(self):  # "1의2"
        return f"{self.no}의{self.sub}" if self.sub else str(self.no)

    @property
    def keypart(self):  # "1-2"
        return f"{self.no}-{self.sub}" if self.sub else str(self.no)

    def lines(self):
        return [self.text] + [m.text for m in self.moks]


@dataclass
class Hang:
    no: int | None   # 번호 없는 항(항 없이 호만 있는 조)은 None
    text: str        # 항 글 첫 줄(원숫자 포함). 번호 없는 항이면 ""
    hos: list[Ho] = field(default_factory=list)


@dataclass
class Article:
    jo: int
    jo_sub: int
    title: str
    header: str      # "제15조(개인정보의 수집ㆍ이용)" 또는 "제8조"
    lead: str        # 조문내용에서 header 뒤 글(항 없는 조의 본문 첫 부분)
    hangs: list[Hang]
    raw_content: str  # 조문내용 원문
    effective: str = ""
    ref_note: str = ""   # 조문참고자료

    @property
    def label(self):
        return f"제{self.jo}조의{self.jo_sub}" if self.jo_sub else f"제{self.jo}조"

    @property
    def keypart(self):
        return f"{self.jo}-{self.jo_sub}" if self.jo_sub else str(self.jo)


HEADER = re.compile(r"^\s*(제(\d+)조(?:의(\d+))?(?:\(([^)]*(?:\([^)]*\)[^)]*)*)\))?)\s*")


def split_header(content: str):
    m = HEADER.match(content)
    if not m:
        return None
    return m.group(1), content[m.end():]


def ho_no(raw: str):
    m = re.match(r"\s*(\d+)(?:의(\d+))?\.", raw)
    return (int(m.group(1)), int(m.group(2) or 0)) if m else None


# ---------------------------------------------------------------- 법령(eflaw)
def parse_eflaw_articles(units):
    """조문단위 리스트 → Article 리스트. '전문'(장·절 제목)은 건너뛴다."""
    arts = []
    for u in units:
        if u.get("조문여부") != "조문":
            continue
        content = flat_text(u.get("조문내용")).strip()
        sh = split_header(content)
        if not sh:
            raise ValueError("조문 머리를 읽지 못함: " + content[:40])
        header, rest = sh
        jo = int(u["조문번호"])
        sub = int(u.get("조문가지번호") or 0)
        hangs = []
        for h in as_list(u.get("항")):
            htext = flat_text(h.get("항내용")).strip()
            hno = None
            if h.get("항번호"):
                hno = hang_no(h["항번호"])
            hos = []
            for o in as_list(h.get("호")):
                otext = flat_text(o.get("호내용")).strip()
                n = ho_no(o.get("호번호") or otext)
                if n is None:
                    raise ValueError(f"{header} 호 번호를 읽지 못함: {otext[:30]}")
                moks = [Mok(re.sub(r"\W", "", flat_text(m.get("목번호")))[:1], flat_text(m.get("목내용")).strip())
                        for m in as_list(o.get("목"))]
                hos.append(Ho(n[0], n[1], otext, moks))
            hangs.append(Hang(hno, htext, hos))
        arts.append(Article(jo, sub, flat_text(u.get("조문제목")).strip(), header.strip(), rest.strip(), hangs,
                            content, u.get("조문시행일자", ""), flat_text(u.get("조문참고자료")).strip()))
    return arts


# ---------------------------------------------------------------- 행정규칙(admrul)
def _find_next(text, start, labels):
    """text[start:]에서 labels 중 가장 먼저 나오는 것의 위치. 앞 글자가 숫자면 안 된다(12.의 2. 방지)."""
    best = None
    for lab in labels:
        for m in re.finditer(re.escape(lab), text[start:]):
            pos = start + m.start()
            prev = text[pos - 1] if pos > 0 else ""
            if prev.isdigit() or prev == "의" or prev == "제":
                continue
            if best is None or pos < best[0]:
                best = (pos, lab)
            break
    return best


def split_items(body: str):
    """'다음 각 호' 뒤로 1. 2. 1의2. … 를 차례로 찾는다. (lead, [Ho]) 반환."""
    first = _find_next(body, 0, ["1. "])
    if first is None:
        return body, []
    lead = body[:first[0]]
    hos, cur_pos, cur = [], first[0], (1, 0)
    while True:
        n, s = cur
        nexts = [f"{n + 1}. ", f"{n}의{s + 1 if s else 2}. "]
        if s:
            nexts.append(f"{n}의{s + 1}. ")
        nx = _find_next(body, cur_pos + 2, nexts)
        end = nx[0] if nx else len(body)
        seg = body[cur_pos:end].strip()
        moks = []
        mfirst = _find_next(seg, 0, ["가. "])
        if mfirst:
            head = seg[:mfirst[0]].strip()
            mpos, mi = mfirst[0], 0
            while True:
                if mi + 1 < len(MOK_ORDER):
                    mn = _find_next(seg, mpos + 2, [MOK_ORDER[mi + 1] + ". "])
                else:
                    mn = None
                mend = mn[0] if mn else len(seg)
                moks.append(Mok(MOK_ORDER[mi], seg[mpos:mend].strip()))
                if not mn:
                    break
                mpos, mi = mn[0], mi + 1
            seg = head
        hos.append(Ho(n, s, seg, moks))
        if not nx:
            break
        lab = nx[1]
        cur_pos = nx[0]
        m = re.match(r"(\d+)(?:의(\d+))?\.", lab)
        cur = (int(m.group(1)), int(m.group(2) or 0))
    return lead, hos


def parse_admrul_articles(lines):
    arts = []
    for raw in as_list(lines):
        content = flat_text(raw).strip()
        sh = split_header(content)
        if not sh or not re.match(r"제\d+조", content):
            continue  # 장 제목 등
        header, rest = sh
        m = HEADER.match(content)
        jo, sub, title = int(m.group(2)), int(m.group(3) or 0), (m.group(4) or "")
        hangs = []
        parts = re.split(r"(?=[①-⑳])", rest)
        if len(parts) > 1 and parts[0].strip() == "":
            for p in parts[1:]:
                p = p.strip()
                no = CIRCLED.index(p[0]) + 1
                lead, hos = split_items(p)
                hangs.append(Hang(no, lead.strip(), hos))
            lead_text = ""
        else:
            lead_text, hos = split_items(rest)
            if hos:
                hangs.append(Hang(None, "", hos))
            lead_text = lead_text.strip()
        arts.append(Article(jo, sub, title, header.strip(), lead_text, hangs, content))
    return arts


def deleted_date(text: str):
    """삭제된 단위면 삭제 날짜 글(날짜가 없으면 "?"), 아니면 None."""
    m = DELETED.match(text)
    if not m:
        return None
    return m.group(1) or "?"
