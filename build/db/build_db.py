#!/usr/bin/env python3
"""data/원천/<날짜>/의 법제처 API 응답으로 privacyask.db를 처음부터 다시 만든다.

같은 원천이면 같은 DB가 나온다(현재 시각·난수를 쓰지 않는다).
실행(build/에서): python3 db/build_db.py [--source ../data/원천/20261125] [--out ../data/out/db/privacyask.db]
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import sqlite3
import sys
import unicodedata
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))  # build/: paths.py
import paths as P  # noqa: E402
sys.path.insert(0, str(HERE))
import lawparse as lp  # noqa: E402
import guides as gd  # noqa: E402
import packages as pkgs  # noqa: E402

BUNDLE_KB = P.BUNDLE_KB  # 안내서 입력(확정 묶음, 읽기만)

RULESET = "chunk-rule-v1"
BODY_BUDGET = 820      # 항 본문(개정 표시 뺀 글자 수)이 이보다 길면 호 묶음으로 나눈다
DEFINITION_TITLE = "정의"  # 정의 조는 호마다 나눈다(확정 묶음 1단계와 같은 원칙)

# 시행령·고시가 "법"이라고 부르는 법률, 고시가 "영"이라고 부르는 시행령
PARENT = {2: 1, 4: 3, 6: 5, 19: 18, 21: 20, 23: 22, 25: 24, 28: 27, 30: 29, 34: 33, 16: 1, 17: 1}
YEONG = {16: 2, 17: 2}

AMEND = re.compile(r"\s*<(?:개정|신설|전문개정|본조신설|제목개정|종전|타법개정)[^>]*>")
BOX = re.compile(r"^[─-╿\s]+$")


def sha(b: bytes | str) -> str:
    return hashlib.sha256(b.encode("utf-8") if isinstance(b, str) else b).hexdigest()


def norm(s) -> str:
    return re.sub(r"\s+", "", unicodedata.normalize("NFC", str(s)))


def ymd(s):
    s = re.sub(r"\D", "", str(s or ""))
    return f"{s[:4]}-{s[4:6]}-{s[6:8]}" if len(s) == 8 else None


def del_date(text):
    d = lp.deleted_date(text)
    if not d:
        return None
    if d == "?":
        return "날짜없음"
    m = re.match(r"(\d{4})\.\s*(\d{1,2})\.\s*(\d{1,2})", d)
    return f"{m.group(1)}-{int(m.group(2)):02d}-{int(m.group(3)):02d}" if m else d


def strip_amend(s):
    return AMEND.sub("", s)


# ------------------------------------------------------------------ 키와 인용 표기
def ho_key(no, sub):
    return f"{no}-{sub}" if sub else str(no)


def ho_cite(no, sub):
    return f"제{no}호의{sub}" if sub else f"제{no}호"


def parse_ho_key(k):
    if k is None:
        return None
    a, _, b = k.partition("-")
    return (int(a), int(b or 0))


def make_key(keyname, jo, jo_sub, hang=None, ho=None, mok=None):
    j = f"{jo}-{jo_sub}" if jo_sub else str(jo)
    return f"{keyname}|{j}|{hang if hang else ''}|{ho or ''}|{mok or ''}"


def cite_from_parts(jo, jo_sub, hang=None, ho_from=None, ho_to=None):
    """구조 키 → 기존 인용 뒷부분(article). 예: 제64조의2 제1항 제8호~제9호"""
    s = f"제{jo}조" + (f"의{jo_sub}" if jo_sub else "")
    if hang:
        s += f" 제{hang}항"
    if ho_from:
        a = ho_cite(*parse_ho_key(ho_from))
        if ho_to and ho_to != ho_from:
            s += f" {a}~{ho_cite(*parse_ho_key(ho_to))}"
        else:
            s += f" {a}"
    return s


def parse_key(unit_key):
    law, j, h, o, m = unit_key.split("|")
    jo, _, sub = j.partition("-")
    ho_from = ho_to = None
    if o:
        ho_from, _, ho_to = o.partition("~")
        ho_to = ho_to or ho_from
    return law, int(jo), int(sub or 0), (int(h) if h else None), ho_from, ho_to, (m or None)


# ------------------------------------------------------------------ 청크 만들기
def build_units(arts, keyname, src_file):
    """Article 목록 → 청크 dict 목록(현행 버전 기준 상태: CURRENT 또는 삭제된 단위 ARCHIVED)."""
    out = []

    def add(a, hang, hos, text, status="CURRENT", eff_to=None):
        ho_from = ho_key(hos[0].no, hos[0].sub) if hos else None
        ho_to = ho_key(hos[-1].no, hos[-1].sub) if hos else None
        ho = None if not hos else (ho_from if ho_from == ho_to else f"{ho_from}~{ho_to}")
        if eff_to == "날짜없음":
            eff_to = None
        out.append(dict(unit_key=make_key(keyname, a.jo, a.jo_sub, hang, ho), jo=a.jo, jo_sub=a.jo_sub, hang=hang,
                        ho=ho, ho_from=ho_from, ho_to=ho_to,
                        article=cite_from_parts(a.jo, a.jo_sub, hang, ho_from, ho_to),
                        title=a.title, text=text, status=status, effective_to=eff_to, source_file=src_file))

    for a in arts:
        if not a.hangs:
            d = del_date(a.raw_content)
            if not d and a.raw_content.strip() == a.header and not a.lead:
                # 본문 없이 머리만 남은 조(다른 조로 이동). 참고자료의 <날짜>를 끝난 날로 본다
                m = re.search(r"<(\d{4})\.(\d{1,2})\.(\d{1,2})>", a.ref_note)
                add(a, None, [], (a.raw_content.strip() + " " + a.ref_note).strip(), "ARCHIVED",
                    f"{m.group(1)}-{int(m.group(2)):02d}-{int(m.group(3)):02d}" if m else None)
                continue
            add(a, None, [], a.raw_content.strip(), "ARCHIVED" if d else "CURRENT", d)
            continue
        for h in a.hangs:
            if h.no and del_date(h.text):
                add(a, h.no, [], f"{a.header}\n{h.text}", "ARCHIVED", del_date(h.text))
                continue
            lead = h.text if h.no else a.lead
            head = "\n".join(x for x in (a.header, lead) if x)
            live = [o for o in h.hos if not del_date(o.text)]
            for o in h.hos:
                if del_date(o.text):
                    add(a, h.no, [o], f"{head}\n{o.text}", "ARCHIVED", del_date(o.text))
            if not live:
                add(a, h.no, [], "\n".join([head] + [x for o in h.hos for x in o.lines()]))
                continue
            body = len(strip_amend(lead)) + sum(len("\n".join(strip_amend(x) for x in o.lines())) + 1 for o in live)
            if a.title == DEFINITION_TITLE:
                groups = [[o] for o in live]
            elif body <= BODY_BUDGET:
                groups = None
            else:
                groups, cur, size = [], [], len(strip_amend(lead))
                for o in live:
                    n = len("\n".join(strip_amend(x) for x in o.lines())) + 1
                    if cur and size + n > BODY_BUDGET:
                        groups.append(cur)
                        cur, size = [], len(strip_amend(lead))
                    cur.append(o)
                    size += n
                groups.append(cur)
            if groups is None:
                # 나누지 않는 항은 원문 줄을 그대로 둔다(삭제된 호 줄 포함, 확정 묶음과 같음)
                add(a, h.no, [], "\n".join([head] + [x for o in h.hos for x in o.lines()]))
            else:
                for g in groups:
                    add(a, h.no, g, "\n".join([head] + [x for o in g for x in o.lines()]))
    return out


# ------------------------------------------------------------------ 별표
MARKER = re.compile(r"^(?:\d+(?:의\d+)?\.\s+\S|[가-하]\.\s+\S|\d+\)|[가-하]\)\s|\(\d+\)|[①-⑳]|※|■|□|○|●|◇|◆|▶|-|ㆍ|·|비고|\[|<|\*)")
EDGE_PUNCT = ".,:;)]」』>"


class Vocab:
    """띄어쓰기가 맞는 글(조문 본문, 별표 줄의 가운데 낱말)에서 낱말·두 낱말 빈도를 센다."""

    def __init__(self):
        self.word, self.pair = {}, {}

    def add(self, text, edges=True):
        for line in text.split("\n"):
            toks = line.split()
            if not edges:
                toks = toks[1:-1]
            for t in toks:
                self.word[t] = self.word.get(t, 0) + 1
            for x, y in zip(toks, toks[1:]):
                self.pair[(x, y)] = self.pair.get((x, y), 0) + 1

    def glue(self, a, b):
        """고정폭 줄바꿈 a|b를 이을 때 공백을 넣을지 정한다. 순서대로:
        1) 다음 줄이 조사·어미 조각(을, 으로, 의2 …)이거나 조문 번호가 이어지면(제75조|제2항, 제23|조) 붙인다
        2) 앞이 문장부호로 끝나거나 다음이 괄호로 시작하면 띄운다. 조문 인용 앞 낱말(법|제31조)도 띄운다
        3) 붙인 낱말이 두 낱말보다 흔하면 붙인다. 둘 다 처음 보는 조각이면 낱말 중간에서 끊긴 것으로 보고 붙인다"""
        if not a or not b:
            return a + b
        x, y = a.split()[-1], b.split()[0]
        if NUMERIC.match(x) or NUMERIC.match(y):
            return a + " " + b
        y0 = y.rstrip(".,:;)")
        if y0 in PARTICLES or re.match(r"의\d", y):
            return a + b
        if ((re.search(r"(?:조|항|호|목|조의\d+)$", x) and re.match(r"제\d", y))
                or (x[-1].isdigit() and re.match(r"[조항호]", y)) or (x.endswith("제") and y[0].isdigit())):
            return a + b
        if a[-1] in EDGE_PUNCT or b[0] in "(「『<[［":
            return a + " " + b
        if re.match(r"제\d", y) and x[-1] not in "조항호목0123456789":
            return a + " " + b  # "법 제31조"처럼 조문 인용 앞 낱말은 띄운다
        joined = self.word.get(x + y, 0)
        spaced = self.pair.get((x, y), 0)
        if joined > spaced:
            return a + b
        if joined == spaced == 0 and x not in self.word and y not in self.word:
            return a + b
        return a + " " + b


NUMERIC = re.compile(r"^[\d,.\s~∼\-%]+(?:원|만원|억원|개월|일|년|명|건|배|분의\s?\d+)?$")
PARTICLES = {"을", "를", "은", "는", "의", "에", "에서", "에게", "에는", "으로", "로", "으로서", "로서", "과", "와",
             "이", "가", "이나", "나", "도", "만", "까지", "부터", "다", "함"}


def reflow(lines, vocab):
    """API 별표 글(고정폭 줄바꿈, 상자 표)을 읽기용 글로 바꾼다.
    - 빈 줄·번호(1. 가. 1) ※ ■ 비고 …)로 시작하는 줄은 새 줄, 나머지 줄은 앞 줄에 잇는다.
    - 상자 표는 행마다 '칸 | 칸'으로, 칸 안 줄바꿈도 같은 규칙으로 잇는다."""
    out, para, cur = [], None, None

    def flush_para():
        nonlocal para
        if para:
            out.append(para)
        para = None

    def flush_row():
        nonlocal cur
        if cur and any(cur):
            out.append(" | ".join(c for c in cur))
        cur = None

    for raw in lines:
        line = raw.strip().replace("┃", "│")
        if "│" in line:
            flush_para()
            cells = [re.sub(r"[\u2500-\u257F]+", "", c).strip() for c in line.strip("│").split("│")]
            if not any(cells):
                continue
            # 숫자 칸이나 번호로 시작하는 칸이 있으면 새 행(한 상자 안에 여러 행이 든 과태료표 등).
            # 글만 이어지는 줄은 윗 행 칸에 잇는다
            new_row = (cur is None or len(cells) != len(cur)
                       or any(NUMERIC.match(c) or MARKER.match(c) for c in cells if c))
            if new_row:
                flush_row()
                cur = [""] * len(cells)
            for i, c in enumerate(cells):
                if c:
                    cur[i] = vocab.glue(cur[i], c) if cur[i] else c
            continue
        if not line or BOX.match(line):
            flush_row()
            flush_para()
            continue
        if any("\u2500" <= ch <= "\u257F" for ch in line):
            # 표 테두리 줄 옆에 본문 글이 같이 오는 경우(API 배치): 테두리 문자를 빼고 글만 쓴다
            flush_row()
            flush_para()
            line = re.sub(r"[\u2500-\u257F]+", " ", line).strip()
            if not line:
                continue
        flush_row()
        if para is None or MARKER.match(line):
            flush_para()
            para = line
        else:
            para = vocab.glue(para, line)
    flush_row()
    flush_para()
    return "\n".join(out)


def annex_units(body_annex, keyname, src_file):
    out = []
    for u in lp.as_list((body_annex or {}).get("별표단위")):
        kind = "서식" if (u.get("별표구분") or "").strip() in ("서식", "별지", "별지서식") else "별표"
        no, sub = int(u.get("별표번호") or 0), int(u.get("별표가지번호") or 0)
        if kind == "별표":
            label = "별표" + (f" {no}" if no else "") + (f"의{sub}" if sub else "")
        else:
            label = (f"별지 제{no}호" + (f"의{sub}" if sub else "") + "서식") if no else "별지 서식"
        lines = []
        for part in lp.as_list(u.get("별표내용")):
            for ln in lp.as_list(part):
                if isinstance(ln, list):
                    lines.extend(str(x) for x in ln)
                else:
                    lines.append(str(ln))
        text = "\n".join(lines) if lines else None
        title = lp.flat_text(u.get("별표제목")).strip()
        rel = re.search(r"\(([^()]*관련)\)\s*$", title)
        deleted = (title.strip().startswith("삭제") or (text or "").strip() in ("삭제", "")
                   or re.search(r"\]\s*삭제\s*<[^>]*>\s*$", text or "") is not None)
        out.append(dict(deleted=deleted, annex_key=f"{keyname}|{kind}|{ho_key(no, sub)}", kind=kind, annex_no=no, annex_sub=sub,
                        label=label, title=title, text=text, _lines=lines,
                        effective_from=ymd(u.get("별표시행일자")), text_hash=sha(text) if text else None,
                        pdf_link=u.get("별표서식PDF파일링크"), file_link=u.get("별표서식파일링크"),
                        related=rel.group(1) if rel else None, source_file=src_file))
    return out


# ------------------------------------------------------------------ 연결(본문 참조)
LAWPAREN = r"(?:\([^()]{0,40}\)\s*)?"   # 「개인정보 보호법」(이하 "법"이라 한다) 제29조
REF = re.compile(
    r"(?:「(?P<law>[^」]{1,80})」\s*(?P<lawsub>시행령|시행규칙)?\s*" + LAWPAREN +
    r"|(?<![가-힣\])])(?P<kw>같은\s?법\s?시행령|같은\s?법|같은\s?영|동법\s?시행령|동법|이\s?법|이\s?영|법\s?시행령|법|영"
    r"|같은\s?조|같은\s?항|동조|동항|전조|전항|본조)\s*" + LAWPAREN +
    r"|(?<![가-힣「」])(?P<plain>[가-힣]{1,20}법)\s+(?=제\d+조)"
    r")?"
    r"(?:제(?P<jo>\d+)조(?:의(?P<josub>\d+))?)?\s?"
    r"(?:제(?P<hang>\d+)항)?\s?"
    r"(?:제(?P<ho>\d+)호(?:의(?P<hosub>\d+))?)?\s?"
    r"(?:(?P<mok>[가-하])목)?")
# 옛 법령(통신비밀보호법·상법 등)은 「」 없이 "형법 제92조"처럼 쓰고, 같은 줄에서 그 법 조문을 이어 쓴다
PLAIN_LAW = re.compile(r"(?<![가-힣ㆍ「」])([가-힣ㆍ]{1,40}(?:법률|법))(?=\s*(?:제\d+(?:조|편|장|절)|에\s?규정된))")
AFTER_ANNEX = re.compile(r"별표\s?\d+(?:의\d+)?\s*$")
NOT_LAW = {"방법", "불법", "위법", "적법", "입법", "편법", "합법", "이법", "같은법", "동법"}
ANNEX_REF = re.compile(
    r"(?:「(?P<law>[^」]{1,80})」\s*(?P<lawsub>시행령)?\s*|(?<![가-힣])(?P<kw>법|영|같은\s?법)\s*)?"
    r"(?:\[?(?<!식)별표\s?(?P<no>\d+)?(?:의(?P<sub>\d+))?\]?|별지\s?(?:제(?P<fno>\d+)호(?:의(?P<fsub>\d+))?\s?)?서식)")
CONNECT = re.compile(r"^(?:[\s,ㆍ·]|\([^()]{0,30}\)|및|또는|와|과|부터|까지|내지|이나|전단|후단|본문|단서|각\s?호\s?외의\s?부분)*$")
# 「형법」 제2편 중 … 제92조처럼 다른 법의 편·장을 먼저 말하고 그 법 조문을 이어 쓰는 경우
LAW_DIVISION = re.compile(r"(?:「(?P<b>[^」]+)」|(?<![가-힣「」])(?P<p>[가-힣]{1,20}법))\s*(?:제\d+(?:편|장|절)|에\s?규정된)")
QUOTED = re.compile(r'"[^"\n]*"|“[^”\n]*”')
INSTRUMENT_NO = re.compile(r"(법률|대통령령|총리령|부령|고시|훈령|예규|규칙|조약)\s*$")
JUNYONG = re.compile(r"^\s*(?:까지)?\s*(?:\([^)]*\)\s*)?(?:의\s*규정\s*)?(?:을|를|은|는)?\s*(?:각각\s*)?준용")


SKIPPED = {}  # 빌드 정보에 남기는 건너뛴 참조 수


class Resolver:
    def __init__(self, docs, chunks, annexes):
        self.docs = docs
        self.by_title = {}
        for d in docs.values():
            self.by_title[norm(d["title"])] = d["doc_id"]
            self.by_title.setdefault(norm(d["law_name"]), d["doc_id"])
        self.cur, self.arch, self.pend = {}, {}, {}
        for c in chunks:
            bucket = {"CURRENT": self.cur, "ARCHIVED": self.arch, "PENDING": self.pend}[c["status"]]
            bucket.setdefault((c["doc_id"], c["jo"], c["jo_sub"]), []).append(c)
        self.annex = {(a["doc_id"], a["kind"], a["annex_no"], a["annex_sub"]): a
                      for a in annexes if a["status"] == "CURRENT"}
        self.order = {}
        for (d, jo, sub) in sorted(set(self.cur) | set(self.arch)):
            self.order.setdefault(d, []).append((jo, sub))

    def prev_article(self, doc, jo, sub):
        arts = self.order.get(doc, [])
        i = arts.index((jo, sub)) if (jo, sub) in arts else -1
        return arts[i - 1] if i > 0 else None

    def doc_for_title(self, title):
        return self.by_title.get(norm(title))

    def find(self, doc, jo, jo_sub, hang=None, ho=None):
        """(청크, resolution, 이유). 가장 세밀하게 덮는 현행 청크를 찾는다."""
        cands = self.cur.get((doc, jo, jo_sub), [])
        if not cands:
            if (doc, jo, jo_sub) in self.arch:
                return None, "미해결", "삭제된_조문"
            if (doc, jo, jo_sub) in self.pend:
                return None, "미해결", "시행예정_조문"
            return None, "미해결", "대상없음"
        if hang:
            c2 = [c for c in cands if c["hang"] == hang]
            if not c2:
                arch = [c for c in self.arch.get((doc, jo, jo_sub), []) if c["hang"] == hang]
                return (None, "미해결", "삭제된_조문") if arch else (cands[0], "상위단위", "항_없음")
        else:
            c2 = cands
        if ho:
            t = parse_ho_key(ho)
            c3 = [c for c in c2 if c["ho_from"] is None or parse_ho_key(c["ho_from"]) <= t <= parse_ho_key(c["ho_to"])]
            if not c3:
                arch = [c for c in self.arch.get((doc, jo, jo_sub), []) if c["hang"] == hang and c["ho"] == ho]
                return (None, "미해결", "삭제된_조문") if arch else (c2[0], "상위단위", "호_없음")
            c3.sort(key=lambda c: c["ho_from"] is None)
            return c3[0], "정확", None
        return c2[0], "정확", None


def extract_text_links(c, docs, R: Resolver, keynames, skip_header=True):
    """청크 하나의 본문에서 참조를 뽑는다."""
    doc = c["doc_id"]
    text = c["text"]
    m0 = lp.HEADER.match(text.split("\n", 1)[0]) if skip_header else None
    skip_to = m0.end(1) if m0 else 0
    links = []
    prev = None          # 앞 참조 dict
    last_law = None      # ('kb', doc_id) / ('ext', 이름)
    quoted = [(q.start(), q.end()) for q in QUOTED.finditer(text)]
    for m in REF.finditer(text):
        g = m.groupdict()
        kw = re.sub(r"\s", "", g["kw"] or "")
        if any(a < m.start() < b for a, b in quoted) and (g["jo"] or g["hang"] or g["ho"]):
            # 따옴표 안(다른 법 문구를 바꿔 읽는 준용 규정 등)의 조문 번호는 이 법 조문이 아니다
            SKIPPED["따옴표_안_참조"] = SKIPPED.get("따옴표_안_참조", 0) + 1
            continue
        if not (g["jo"] or g["hang"] or g["ho"] or kw in ("전조", "전항")):
            if g["law"]:
                last_law = law_ctx(g["law"], g["lawsub"], R, docs)
            continue
        if m.start() < skip_to:
            continue  # 청크 첫 줄의 조 머리(제15조(…))
        if not g["jo"] and AFTER_ANNEX.search(text[max(0, m.start() - 12):m.start()]):
            continue  # "별표 4 제5호바목"은 별표 안의 번호(별표 연결로 따로 잡힘)
        if g["ho"] and not g["jo"] and not g["hang"] and INSTRUMENT_NO.search(text[:m.start()]):
            continue  # "법률 제12345호" 같은 공포번호
        gap = text[prev["end"]:m.start()] if prev else ""
        connected = prev is not None and CONNECT.match(gap) is not None
        # 앞 참조 바로 뒤 괄호 안: 예) 법 제25조제6항(제25조의2제4항에 따라 준용되는 …) → 같은 법
        g2 = gap.lstrip()
        inparen = (prev is not None and g2.startswith("(") and ")" not in g2 and not g2.startswith("(이하"))
        # 어느 법인가
        plain = g["plain"] if g["plain"] and g["plain"] not in NOT_LAW else None
        if g["law"]:
            target = law_ctx(g["law"], g["lawsub"], R, docs)
            last_law = target
        elif plain:
            target = law_ctx(plain, None, R, docs)
            last_law = target
        elif kw in ("같은법", "동법", "같은영"):
            target = last_law or ("kb", doc)
        elif kw in ("같은법시행령", "동법시행령"):
            target = sub_law(last_law, R, docs) if last_law else ("kb", doc)
        elif kw in ("이법", "이영", "전조", "전항", "본조"):
            target = ("kb", doc)
        elif kw == "법":
            target = ("kb", PARENT.get(doc, doc))
        elif kw in ("법시행령", "영"):
            target = ("kb", YEONG.get(doc, doc))
        elif kw in ("같은조", "같은항", "동조", "동항") and prev:
            target = prev["target"]
        elif connected or inparen:
            target = prev["target"]
        else:
            # 같은 줄 앞에 「」 없는 법 이름(옛 표기)이나 다른 법의 편·장 언급이 있으면 그 법 조문으로 본다
            line_start = text.rfind("\n", 0, m.start()) + 1
            cands = [(x.start(), x.group(1)) for x in PLAIN_LAW.finditer(text, line_start, m.start())
                     if x.group(1) not in NOT_LAW]
            cands += [(x.start(), x.group("b") or x.group("p")) for x in LAW_DIVISION.finditer(text, line_start, m.start())
                      if (x.group("b") or x.group("p")) not in NOT_LAW]
            target = law_ctx(max(cands)[1], None, R, docs) if cands else ("kb", doc)
        # 빠진 상위 단위 채우기
        jo = int(g["jo"]) if g["jo"] else None
        josub = int(g["josub"] or 0)
        hang = int(g["hang"]) if g["hang"] else None
        ho = ho_key(int(g["ho"]), int(g["hosub"] or 0)) if g["ho"] else None
        if kw == "전조":
            pa = R.prev_article(doc, c["jo"], c["jo_sub"])
            if not pa:
                continue
            jo, josub = pa
        elif kw == "전항":
            if not c.get("hang") or c["hang"] < 2:
                continue
            jo, josub, hang = c["jo"], c["jo_sub"], c["hang"] - 1
        elif kw == "본조":
            jo, josub = c["jo"], c["jo_sub"]
        same = kw in ("같은조", "같은항", "동조", "동항")
        base = prev if (prev and (connected or same)) else None
        if jo is None:
            if base:
                jo, josub = base["jo"], base["josub"]
                if hang is None and ho and kw not in ("같은조", "동조"):
                    hang = base["hang"]
                if kw in ("같은항", "동항"):
                    hang = base["hang"]
            elif target == ("kb", doc):
                jo, josub = c["jo"], c["jo_sub"]
                if hang is None and ho:
                    hang = c.get("hang")
            else:
                continue
        ref = dict(target=target, jo=jo, josub=josub, hang=hang, ho=ho, mok=g["mok"], end=m.end(), start=m.start())
        prev = ref
        links.append(ref)
    # 종류(준용) 판정: 이어진 참조 묶음 뒤에 '준용'이 오면 묶음 전체가 준용
    out = []
    i = 0
    while i < len(links):
        j = i
        while j + 1 < len(links) and CONNECT.match(text[links[j]["end"]:links[j + 1]["start"]]):
            j += 1
        kind = "준용" if JUNYONG.match(text[links[j]["end"]:links[j]["end"] + 40]) else "본문참조"
        for r in links[i:j + 1]:
            out.append(make_link(kind, c, r, R, keynames, text[max(0, r["start"] - 10):r["end"] + 10], "본문"))
        i = j + 1
    # 별표 참조
    last_law = None
    for m in ANNEX_REF.finditer(text):
        g = m.groupdict()
        kw = re.sub(r"\s", "", g["kw"] or "")
        if g["law"]:
            target = law_ctx(g["law"], g["lawsub"], R, docs)
        elif kw == "법":
            target = ("kb", PARENT.get(doc, doc))
        elif kw == "영":
            target = ("kb", YEONG.get(doc, doc))
        else:
            target = ("kb", doc)
        if "별지" in m.group(0):
            kind, no, sub = "서식", int(g["fno"] or 0), int(g["fsub"] or 0)
        else:
            kind, no, sub = "별표", int(g["no"] or 0), int(g["sub"] or 0)
        out.append(annex_link(c["unit_key"], c["chunk_id"], None, target, kind, no, sub, R, keynames,
                              text[max(0, m.start() - 10):m.end() + 10], "본문"))
    return out


def law_ctx(name, sub, R, docs):
    full = name + (" " + sub if sub else "")
    d = R.doc_for_title(full)
    return ("kb", d) if d else ("ext", full)


def sub_law(last, R, docs):
    if last[0] == "kb":
        d = R.doc_for_title(docs[last[1]]["title"] + " 시행령")
        return ("kb", d) if d else ("ext", docs[last[1]]["title"] + " 시행령")
    return ("ext", last[1] + " 시행령")


def make_link(kind, c, r, R, keynames, evidence, origin):
    t = r["target"]
    if t[0] == "ext":
        dkey = "밖:" + make_key(norm(t[1]), r["jo"], r["josub"], r["hang"], r["ho"], r["mok"])
        return dict(link_type=kind, src_key=c["unit_key"], src_chunk_id=c["chunk_id"], src_annex_id=None,
                    dst_key=dkey, dst_doc_id=None, dst_chunk_id=None, dst_annex_id=None, resolution="미해결",
                    unresolved_reason="KB밖_법령", evidence=evidence, origin=origin)
    d = t[1]
    dkey = make_key(keynames[d], r["jo"], r["josub"], r["hang"], r["ho"], r["mok"])
    hit, res, why = R.find(d, r["jo"], r["josub"], r["hang"], r["ho"])
    return dict(link_type=kind, src_key=c["unit_key"], src_chunk_id=c["chunk_id"], src_annex_id=None,
                dst_key=dkey, dst_doc_id=d, dst_chunk_id=hit["chunk_id"] if hit else None, dst_annex_id=None,
                resolution=res, unresolved_reason=why, evidence=evidence, origin=origin)


def annex_link(src_key, src_chunk_id, src_annex_id, target, kind, no, sub, R, keynames, evidence, origin):
    if target[0] == "ext":
        return dict(link_type="별표", src_key=src_key, src_chunk_id=src_chunk_id, src_annex_id=src_annex_id,
                    dst_key=f"밖:{norm(target[1])}|{kind}|{ho_key(no, sub)}", dst_doc_id=None, dst_chunk_id=None,
                    dst_annex_id=None, resolution="미해결", unresolved_reason="KB밖_법령", evidence=evidence,
                    origin=origin)
    d = target[1]
    a = R.annex.get((d, kind, no, sub))
    return dict(link_type="별표", src_key=src_key, src_chunk_id=src_chunk_id, src_annex_id=src_annex_id,
                dst_key=f"{keynames[d]}|{kind}|{ho_key(no, sub)}", dst_doc_id=d, dst_chunk_id=None,
                dst_annex_id=a["annex_id"] if a else None, resolution="정확" if a else "미해결",
                unresolved_reason=None if a else "별표_없음", evidence=evidence, origin=origin)


LOC = re.compile(r"제(\d+)조(?:의(\d+))?(?:제(\d+)항)?(?:제(\d+)호(?:의(\d+))?)?(?:([가-하])목)?")


def delegation_links(doc, data, R, docs, keynames, src_index):
    """lsDelegated 응답 → 위임 연결. 인용법령 항목은 본문에서 직접 뽑으므로 쓰지 않는다."""
    out, skipped = [], {}
    for x in lp.as_list(data.get("lsDelegated", {}).get("법령", {}).get("위임조문정보")):
        for w in lp.as_list(x.get("위임정보")):
            kinds = w.get("위임구분")
            kinds_l = kinds if isinstance(kinds, list) else [kinds]
            kinds_set = set(str(k) for k in kinds_l)
            if kinds_set == {"인용법령"}:
                continue
            if kinds_set <= {"시행령", "시행규칙"}:
                titles = w.get("위임법령제목")
                titles_l = titles if isinstance(titles, list) else [titles]
                ambiguous = len(set(titles_l)) > 1
                for j in lp.as_list(w.get("위임법령조문정보")):
                    src = LOC.match(j.get("조항호목", ""))
                    if not src or str(j.get("위임법령조문번호")) == "0":
                        continue
                    sc = src_chunk(doc, src, R)
                    if sc is None:
                        skipped["출발_조문_없음"] = skipped.get("출발_조문_없음", 0) + 1
                        continue
                    djo, dsub = int(j["위임법령조문번호"]), int(j.get("위임법령조문가지번호") or 0)
                    ev = f"{j.get('조항호목')} → {titles_l[0]} 제{djo}조{('의' + str(dsub)) if dsub else ''} ({j.get('라인텍스트', '')})"
                    if ambiguous:
                        out.append(dict(link_type="위임", src_key=sc["unit_key"], src_chunk_id=sc["chunk_id"],
                                        src_annex_id=None, dst_key=f"모호:{'/'.join(sorted(set(titles_l)))}|{djo}",
                                        dst_doc_id=None, dst_chunk_id=None, dst_annex_id=None, resolution="미해결",
                                        unresolved_reason="API대응모호", evidence=ev, origin="lsDelegated"))
                        continue
                    d = R.doc_for_title(titles_l[0])
                    if d is None:
                        out.append(dict(link_type="위임", src_key=sc["unit_key"], src_chunk_id=sc["chunk_id"],
                                        src_annex_id=None, dst_key="밖:" + make_key(norm(titles_l[0]), djo, dsub),
                                        dst_doc_id=None, dst_chunk_id=None, dst_annex_id=None, resolution="미해결",
                                        unresolved_reason="KB밖_법령", evidence=ev, origin="lsDelegated"))
                        continue
                    hit, res, why = R.find(d, djo, dsub)
                    out.append(dict(link_type="위임", src_key=sc["unit_key"], src_chunk_id=sc["chunk_id"],
                                    src_annex_id=None, dst_key=make_key(keynames[d], djo, dsub), dst_doc_id=d,
                                    dst_chunk_id=hit["chunk_id"] if hit else None, dst_annex_id=None,
                                    resolution=res, unresolved_reason=why, evidence=ev, origin="lsDelegated"))
            elif kinds_set == {"위임행정규칙"}:
                for j in lp.as_list(w.get("위임행정규칙조문정보")):
                    src = LOC.match(j.get("조항호목", ""))
                    sc = src_chunk(doc, src, R) if src else None
                    if sc is None:
                        skipped["출발_조문_없음"] = skipped.get("출발_조문_없음", 0) + 1
                        continue
                    t = j.get("위임행정규칙제목", "")
                    d = R.doc_for_title(t)
                    ev = f"{j.get('조항호목')} → {t} ({j.get('링크텍스트', '')})"
                    out.append(dict(link_type="위임", src_key=sc["unit_key"], src_chunk_id=sc["chunk_id"],
                                    src_annex_id=None,
                                    dst_key=(keynames[d] + "||||") if d else "밖:" + norm(t) + "||||",
                                    dst_doc_id=d, dst_chunk_id=None, dst_annex_id=None,
                                    resolution="문서" if d else "미해결",
                                    unresolved_reason=None if d else "KB밖_법령", evidence=ev, origin="lsDelegated"))
            else:
                k = "/".join(sorted(kinds_set))
                skipped[k] = skipped.get(k, 0) + 1
    return out, skipped


def src_chunk(doc, m, R):
    jo, sub = int(m.group(1)), int(m.group(2) or 0)
    hang = int(m.group(3)) if m.group(3) else None
    ho = ho_key(int(m.group(4)), int(m.group(5) or 0)) if m.group(4) else None
    hit, res, _ = R.find(doc, jo, sub, hang, ho)
    return hit


# ------------------------------------------------------------------ 메인
def load_docs(src: Path):
    sel = json.loads(P.SELECTION.read_text())["documents"]
    tgt = {d["doc_id"]: d for d in json.loads(P.TARGETS.read_text())["documents"]}
    docs = {}
    for s in sel:
        t = tgt[s["doc_id"]]
        tag = f"doc{s['doc_id']:02d}"
        body_path = src / tag / "현행" / "response.json"
        req = json.loads((src / tag / "현행" / "request.json").read_text())
        raw = body_path.read_bytes()
        data = json.loads(raw)
        if s["kind"] == "law":
            info = data["법령"]["기본정보"]
            kind = info["법종구분"]["content"] if isinstance(info.get("법종구분"), dict) else info.get("법종구분")
            serial, prom, prom_no, eff = s["current"]["MST"], info["공포일자"], info["공포번호"], info["시행일자"]
            url = f"https://www.law.go.kr/LSW/lsInfoP.do?lsiSeq={serial}"
            assert norm(info["법령명_한글"]) == norm(t["title"]), (t["title"], info["법령명_한글"])
        else:
            info = data["AdmRulService"]["행정규칙기본정보"]
            kind = info["행정규칙종류"]
            serial, prom, prom_no, eff = info["행정규칙일련번호"], info["발령일자"], info["발령번호"], info["시행일자"]
            url = f"https://www.law.go.kr/LSW/admRulInfoP.do?admRulSeq={serial}"
            assert norm(info["행정규칙명"]) == norm(t["title"])
        docs[s["doc_id"]] = dict(
            doc_id=s["doc_id"], source_type="LAW", title=t["title"], law_name=t["law_name"], law_id=s["law_id"],
            law_serial_no=serial, case_no=None, promulgation_date=ymd(prom), effective_date=ymd(eff),
            stage=t["stage"], url=url, content_hash=sha(raw), sync_source="LAW_API", verified=0,
            last_checked_at=req["fetched_at"], created_at=src.name, law_kind=kind,
            api_target="eflaw" if s["kind"] == "law" else "admrul", promulgation_no=prom_no,
            parent_doc_id=PARENT.get(s["doc_id"]),
            use_policy=None, use_policy_by=None, use_policy_at=None, use_policy_note=None,  # apply_policy()가 채움
            _sel=s, _data=data, _tag=tag)
    return docs


POLICY_FILE = P.POLICY_FILE
# 쟁점 꾸러미 입력(D6). 차례대로 넣고 마지막이 지금 판(is_current=1). v1은 고정 기록으로 남긴다
PACKAGE_FILES = P.PACKAGE_FILES


def apply_policy(all_docs, all_chunks, sections, path: Path = POLICY_FILE):
    """사용자 결정 파일 → use_policy·use_scope·정한 사람·날짜·메모. 모든 문서에 값이 있어야 하고,
    ANSWER_BASIS 범위의 접두·절 ID는 이번 빌드의 현행 조문 키·절에 실제로 닿아야 한다."""
    p = json.loads(path.read_text(encoding="utf-8"))
    dec = {int(k): v for k, v in p["documents"].items()}
    by_id = {d["doc_id"]: d for d in all_docs}
    assert set(dec) == set(by_id), set(dec) ^ set(by_id)
    keys = {}
    for c in all_chunks:
        if c["status"] == "CURRENT":
            keys.setdefault(c["doc_id"], []).append(c["unit_key"])
    sec = {s["section_id"]: s["doc_id"] for s in sections}
    for doc_id, v in dec.items():
        d = by_id[doc_id]
        scope = v.get("use_scope")
        if v["use_policy"] == "ANSWER_BASIS":
            assert scope, doc_id
            for k in scope:
                if d["source_type"] == "GUIDE":
                    assert sec.get(k) == doc_id, (doc_id, k)
                else:
                    assert any(u.startswith(k) for u in keys[doc_id]), (doc_id, k)
        else:
            assert not scope, doc_id
        d.update(use_policy=v["use_policy"], use_scope=json.dumps(scope, ensure_ascii=False) if scope else None,
                 use_policy_by=p["decided_by"], use_policy_at=p["decided_at"], use_policy_note=v["note"])


def articles_of(data, kind):
    if kind == "law":
        return lp.parse_eflaw_articles(data["법령"]["조문"]["조문단위"])
    return lp.parse_admrul_articles(data["AdmRulService"]["조문내용"])


def annexes_of(data, kind):
    return data["법령"].get("별표") if kind == "law" else data["AdmRulService"].get("별표")


def build(src: Path, out: Path, bundle: Path = BUNDLE_KB, package_files=PACKAGE_FILES):
    SKIPPED.clear()
    docs = load_docs(src)
    keynames = {d: norm(v["law_name"]) for d, v in docs.items()}
    chunks, annexes = [], []
    for doc_id in sorted(docs):
        d = docs[doc_id]
        kind = d["_sel"]["kind"]
        rel = f"원천/{src.name}/{d['_tag']}/현행/response.json"
        cur = build_units(articles_of(d["_data"], kind), keynames[doc_id], rel)
        for c in cur:
            c.update(doc_id=doc_id, effective_from=d["effective_date"] if c["status"] == "CURRENT" else None)
        cur_text = {c["unit_key"]: c["text"] for c in cur if c["status"] == "CURRENT"}
        pend_seen = {}
        pend = []
        cur_ann = annex_units(annexes_of(d["_data"], kind), keynames[doc_id], rel)
        for a in cur_ann:
            a.update(doc_id=doc_id, status="ARCHIVED" if a.pop("deleted") else "CURRENT")
        cur_ann_hash = {a["annex_key"]: a["text_hash"] for a in cur_ann}
        pend_ann = []
        version_keys = []  # (시행일, 그 판에 있는 키 집합)
        for p in d["_sel"].get("pending", []):
            prel = f"원천/{src.name}/{d['_tag']}/예정_{p['efYd']}/response.json"
            pdata = json.loads((P.DATA / prel).read_text())
            assert pdata["법령"]["기본정보"]["시행일자"] == p["efYd"]
            punits = build_units(articles_of(pdata, "law"), keynames[doc_id], prel)
            version_keys.append((ymd(p["efYd"]), {c["unit_key"] for c in punits}))
            for c in punits:
                if c["status"] != "CURRENT" and c["unit_key"] in cur_text:
                    pass  # 예정본에서 삭제되는 단위 → 아래에서 PENDING(삭제 글)로 넣는다
                if cur_text.get(c["unit_key"]) == c["text"]:
                    continue
                if c["unit_key"] in cur_text and strip_amend(cur_text[c["unit_key"]]) == strip_amend(c["text"]):
                    # <개정 …> 이력 표시만 바뀐 단위(예: 같은 조에 호가 새로 생겨 머리 줄 표시가 바뀜)는
                    # 조문 내용 변경이 아니므로 PENDING으로 넣지 않고 수만 센다
                    SKIPPED["예정본_개정표시만_바뀜"] = SKIPPED.get("예정본_개정표시만_바뀜", 0) + 1
                    continue
                if pend_seen.get(c["unit_key"]) == c["text"]:
                    continue
                if c["status"] == "ARCHIVED" and c["unit_key"] not in cur_text:
                    continue  # 현행에서도 이미 삭제였던 단위
                pend_seen[c["unit_key"]] = c["text"]
                c.update(doc_id=doc_id, status="PENDING", effective_from=ymd(p["efYd"]), effective_to=None)
                pend.append(c)
            for a in annex_units(annexes_of(pdata, "law"), keynames[doc_id], prel):
                a.pop("deleted")
                if cur_ann_hash.get(a["annex_key"]) == a["text_hash"]:
                    continue
                if any(x["annex_key"] == a["annex_key"] and x["text_hash"] == a["text_hash"] for x in pend_ann):
                    continue
                a.update(doc_id=doc_id, status="PENDING", effective_from=ymd(p["efYd"]))
                pend_ann.append(a)
        # 기준일 조회용: 뒤 판에서 키가 사라지는 단위(호 묶음 경계가 바뀌거나 조·항 구조가 바뀜)는
        # 그 판 시행일을 effective_to로 적는다. 그래야 "기준일 D에 시행 중인 판"이
        # effective_from <= D < effective_to(또는 NULL) 중 가장 늦은 effective_from 한 행으로 정해진다.
        for c in cur + pend:
            if c["status"] == "ARCHIVED":
                continue
            ends = [day for day, keys in version_keys
                    if day > (c["effective_from"] or "") and c["unit_key"] not in keys]
            if ends:
                c["effective_to"] = min(ends)
                SKIPPED["예정본에서_키가_사라져_종료일_기록"] = SKIPPED.get("예정본에서_키가_사라져_종료일_기록", 0) + 1
        chunks.extend(cur)
        chunks.extend(pend)
        annexes.extend(cur_ann)
        annexes.extend(pend_ann)
    vocab = Vocab()
    for c in chunks:
        if c["status"] == "CURRENT":
            vocab.add(c["text"])
    for a in annexes:
        vocab.add("\n".join(a["_lines"]), edges=False)
    for a in annexes:
        a["text_rows"] = reflow(a.pop("_lines"), vocab) if a["text"] else None
    for i, c in enumerate(chunks, 1):
        c["chunk_id"] = i
        c["text_hash"] = sha(c["text"])
    for i, a in enumerate(annexes, 1):
        a["annex_id"] = i

    R = Resolver(docs, chunks, annexes)
    links, deleg_skipped = [], {}
    for c in chunks:
        if c["status"] == "CURRENT":
            links.extend(extract_text_links(c, docs, R, keynames))
    for a in annexes:
        if a["status"] != "CURRENT" or not a["related"]:
            continue
        fake = dict(doc_id=a["doc_id"], text=a["related"], jo=0, jo_sub=0, hang=None, unit_key=a["annex_key"],
                    chunk_id=None)
        for l in extract_text_links(fake, docs, R, keynames, skip_header=False):
            if l["link_type"] == "별표":
                continue
            l.update(link_type="별표", src_chunk_id=None, src_annex_id=a["annex_id"], origin="별표제목")
            links.append(l)
    for doc_id in sorted(docs):
        p = src / docs[doc_id]["_tag"] / "위임" / "response.json"
        if p.exists():
            ls, sk = delegation_links(doc_id, json.loads(p.read_text()), R, docs, keynames, None)
            links.extend(ls)
            for k, v in sk.items():
                deleg_skipped[k] = deleg_skipped.get(k, 0) + v
    # 같은 연결은 한 번만, 자기 자신으로 가는 연결은 뺀다
    seen, uniq = set(), []
    for l in links:
        if l["src_chunk_id"] is not None and l["src_chunk_id"] == l["dst_chunk_id"]:
            continue
        k = (l["link_type"], l["src_key"], l["dst_key"], l["origin"])
        if k in seen:
            continue
        seen.add(k)
        uniq.append(l)
    for i, l in enumerate(uniq, 1):
        l["link_id"] = i

    # 안내서 25종: 법령 청크 뒤에 번호를 이어 붙인다(법령 chunk_id는 1단계와 같게 유지)
    gdocs, gchunks, gfiles = gd.load_guides(bundle, src.name)
    for i, c in enumerate(gchunks, len(chunks) + 1):
        c["chunk_id"] = i
    sections = gd.build_sections(gchunks)
    apply_policy([docs[k] for k in docs] + gdocs, chunks + gchunks, sections)

    if out.exists():
        out.unlink()
    out.parent.mkdir(parents=True, exist_ok=True)
    con = sqlite3.connect(out)
    con.executescript((HERE / "schema.sql").read_text())
    doc_cols = ["doc_id", "source_type", "title", "law_name", "law_id", "law_serial_no", "case_no",
                "promulgation_date", "effective_date", "stage", "url", "content_hash", "sync_source", "verified",
                "last_checked_at", "created_at", "law_kind", "api_target", "promulgation_no", "parent_doc_id",
                "use_policy", "use_scope", "use_policy_by", "use_policy_at", "use_policy_note", "guide_version", "page_count",
                "excluded_pages"]
    ins(con, "kb_documents", doc_cols, sorted([docs[k] for k in docs] + gdocs, key=lambda d: d["doc_id"]))
    ins(con, "guide_sections", ["section_id", "doc_id", "parent_id", "depth", "title", "path", "path_text", "ord",
                                "num", "is_leaf", "synthetic", "page_from", "page_to", "chunk_from", "chunk_to",
                                "chunk_count", "own_chunk_count", "char_count", "chunk_runs"], sections)
    ins(con, "kb_chunks", ["chunk_id", "doc_id", "article", "text", "status", "effective_from", "effective_to",
                           "text_hash", "unit_key", "jo", "jo_sub", "hang", "ho", "ho_from", "ho_to", "title",
                           "source_file", "chunk_key", "section_id", "page_from", "page_to"], chunks + gchunks)
    ins(con, "kb_annexes", ["annex_id", "doc_id", "annex_key", "kind", "annex_no", "annex_sub", "label", "title",
                            "text", "text_rows", "status", "effective_from", "text_hash", "pdf_link", "file_link",
                            "related", "source_file"], annexes)
    ins(con, "kb_links", ["link_id", "link_type", "src_key", "src_chunk_id", "src_annex_id", "dst_key",
                          "dst_doc_id", "dst_chunk_id", "dst_annex_id", "resolution", "unresolved_reason",
                          "evidence", "origin"], uniq)
    pstats, last = [], 0
    for i, f in enumerate(package_files):
        st = pkgs.build_packages(con, f, current=(i == len(package_files) - 1), item_id_start=last)
        last = st["last_item_id"]
        pstats.append(dict(st, file=f"수집/{f.name}"))
    info = {"source": f"원천/{src.name}", "chunk_rule": RULESET, "body_budget": str(BODY_BUDGET),
            "skipped_counts": json.dumps(SKIPPED, ensure_ascii=False, sort_keys=True),
            "delegation_skipped": json.dumps(deleg_skipped, ensure_ascii=False, sort_keys=True),
            "guide_bundle": "PrivacyAsk_RAG실험묶음_20261125/kb",
            "guide_bundle_sha256": json.dumps(gfiles, ensure_ascii=False, sort_keys=True),
            "package_files": json.dumps({s["version"]: dict(file=s["file"], sha256=s["package_sha256"], status=s["status"])
                                         for s in pstats}, ensure_ascii=False, sort_keys=True),
            "package_current": pstats[-1]["version"]}
    con.executemany("INSERT INTO build_info VALUES (?,?)", sorted(info.items()))
    con.commit()
    con.execute("VACUUM")
    con.close()
    return dict(docs=len(docs) + len(gdocs), chunks=len(chunks) + len(gchunks), annexes=len(annexes),
                links=len(uniq), guide_chunks=len(gchunks), guide_sections=len(sections),
                packages={s["version"]: s["packages"] for s in pstats},
                package_items={s["version"]: s["package_items"] for s in pstats})


# 기준일 D에 시행 중인 판: 키마다 effective_from <= D < effective_to(없으면 무한) 중 가장 늦은 effective_from 한 행.
# 판단은 오늘이 아니라 사안 기준일(앞으로 할 처리는 시작일)로 한다. ?1 = 기준일 'YYYY-MM-DD', ?2 = 키 접두 LIKE 패턴
IN_FORCE_SQL = """
SELECT c.* FROM kb_chunks c
WHERE c.status IN ('CURRENT','PENDING') AND c.unit_key LIKE ?2
  AND c.effective_from <= ?1 AND (c.effective_to IS NULL OR c.effective_to > ?1)
  AND c.effective_from = (SELECT max(x.effective_from) FROM kb_chunks x
                          WHERE x.unit_key = c.unit_key AND x.status IN ('CURRENT','PENDING')
                            AND x.effective_from <= ?1 AND (x.effective_to IS NULL OR x.effective_to > ?1))
ORDER BY c.chunk_id
"""


def in_force(con, day, key_prefix):
    return con.execute(IN_FORCE_SQL, (day, key_prefix + "%")).fetchall()


def ins(con, table, cols, rows):
    con.executemany(f"INSERT INTO {table} ({','.join(cols)}) VALUES ({','.join('?' * len(cols))})",
                    [[r.get(c) for c in cols] for r in rows])


def content_digest(db: Path) -> str:
    """DB 내용 지문(테이블 행을 정렬해 해시). 재생성 비교용."""
    con = sqlite3.connect(db)
    h = hashlib.sha256()
    for line in con.iterdump():
        h.update(line.encode("utf-8"))
    con.close()
    return h.hexdigest()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--source", default=str(P.SOURCE_DEFAULT))
    ap.add_argument("--out", default=str(P.KB_DB))
    ap.add_argument("--bundle", default=str(BUNDLE_KB), help="확정 묶음 kb 폴더(안내서 입력)")
    a = ap.parse_args()
    stats = build(Path(a.source), Path(a.out), Path(a.bundle))
    print(json.dumps(stats, ensure_ascii=False), content_digest(Path(a.out)))


if __name__ == "__main__":
    main()
