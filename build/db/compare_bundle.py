#!/usr/bin/env python3
"""DB 청크와 확정 묶음(11/25) 법령 청크를 글자 대조한다. (완료 기준 5)

- 같은 버전끼리 비교한다. doc 2·18·19는 묶음 이후 개정되어 DB는 새 현행본이므로,
  원천의 '대조용_묶음버전' 본문을 같은 청크 규칙으로 잘라 비교한다(DB에는 넣지 않음).
- 1) 묶음 청크의 article이 우리 쪽에도 있으면 청크끼리, 2) 없으면(호 묶음 경계가 다름)
  같은 조·항 단위로 모아서 줄 단위로 비교한다.
- 차이는 정규화를 하나씩 더해 같아지는 첫 단계로 분류한다. 남는 것만 '내용 차이'.

출력: data/out/db/대조결과/요약.json, 차이목록.tsv
"""
from __future__ import annotations

import collections
import difflib
import json
import re
import sqlite3
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))  # build/: paths.py
import paths as P  # noqa: E402
sys.path.insert(0, str(HERE))
import build_db as bd  # noqa: E402

BUNDLE = P.BUNDLE_KB
FILES = ["stage1/law_chunks_current.jsonl", "stage2/law2_chunks_current.jsonl"]
COMPARE_VERSION = (2, 18, 19)

# 자동 분류로 안 갈리는 것을 사람이 원문(API 응답·묶음 청크)을 나란히 보고 이유를 적은 것 (2026-11-25)
CHECKED = {
    (18, "제95조의2"): "확인: PDF 추출 오류(제3호의2 글이 머리 줄에 붙고 잘림, 같은 줄 중복). API 글이 온전함",
    (19, "제30조의8 제2항"): "확인: PDF 추출 오류(목 아래 1)~4) 줄이 한 줄로 붙고 4)가 잘림). API 글이 온전함",
    (29, "제55조 제2항"): "확인: PDF 부가 표시(부칙 제2조 시행일 표 1~3호가 항 아래에 붙음). 조문 본문은 같음",
}

MARK = re.compile(r"<(?:개정|신설|전문개정|본조신설|제목개정|종전|타법개정)[^>]*>")
QUOTE = str.maketrans({"“": '"', "”": '"', "‘": "'", "’": "'", "·": "ㆍ", "～": "~", "∼": "~", "‧": "ㆍ"})
CIRC = re.compile(r"[①-⑳㉑-㉟㊱-㊿]|<\d+>")
NOTE = re.compile(r"\[[^\[\]]*(?:시행일|법률\s*제|대통령령\s*제|헌법재판소|본조|종전|전문개정|제목개정|이동|신설함|삭제함|유효기간|유효함|부칙)[^\[\]]*\]")
DEL_LINE = re.compile(r"^\s*(?:\d+(?:의\d+)?|[가-하])\.\s*삭제\s*(?:<[^>]*>)?\s*$", re.M)
HEADING = re.compile(r"\s*제\d+(?:편|장|절|관)(?:의\d+)?\s+[^\n]*$")
TABLE = re.compile(r"[\u2500-\u257F|]|\[계산식\]")
IMG = re.compile(r"<img[^>]*>.*?</img>|<img[^>]*>|</img>", re.S)
PAGE = re.compile(r"법제처\s*\d*\s*국가법령정보센터|국가법령정보센터")


def ws(s):
    return re.sub(r"\s+", "", s)


# (이름, 변환). 앞 단계 변환을 누적해서 적용하고, 비교는 늘 공백을 뺀 글로 한다.
STEPS = [
    ("공백·줄바꿈", lambda s: s),
    ("개정 표시(<개정 …> 형식·유무)", lambda s: MARK.sub("", s)),
    ("따옴표·가운뎃점 기호", lambda s: s.translate(QUOTE)),
    ("원숫자·항 번호 표기(㉑ ↔ <21>)", lambda s: CIRC.sub("", s)),
    ("PDF 부가 표시([시행일 …], [본조신설 …] 등)", lambda s: NOTE.sub("", s)),
    ("삭제된 호 줄(N. 삭제<날짜>) 유무", lambda s: DEL_LINE.sub("", s)),
    ("API 외자 이미지 태그(<img …>)", lambda s: IMG.sub("", s)),
    ("PDF 장·절·관 제목이 조문 끝에 섞임", lambda s: HEADING.sub("", s)),
    ("표 표기(API 상자 그림 ↔ 묶음 '칸 | 칸')", lambda s: TABLE.sub("", s)),
    ("PDF 머리말·쪽 표시", lambda s: PAGE.sub("", s)),
]


def normalize_all(s):
    for _, f in STEPS:
        s = f(s)
    return ws(s)


def classify(ours: str, theirs: str):
    if ours == theirs:
        return "같음"
    a, b = ours, theirs
    for name, f in STEPS:
        a, b = f(a), f(b)
        if ws(a) == ws(b):
            return name
    return "내용 차이"


def snippet(a, b, n=2):
    """정규화를 다 한 뒤에도 남는 차이를 보여 준다."""
    a, b = normalize_all(a), normalize_all(b)
    sm = difflib.SequenceMatcher(None, a, b, autojunk=False)
    out = []
    for op, i1, i2, j1, j2 in sm.get_opcodes():
        if op != "equal":
            out.append(f"[DB]{a[max(0, i1 - 12):i2 + 12]!r} ↔ [묶음]{b[max(0, j1 - 12):j2 + 12]!r}")
        if len(out) >= n:
            break
    return " / ".join(out)


def unit_of(article: str):
    """'제64조의2 제1항 제8호~제9호' → '제64조의2 제1항'"""
    m = re.match(r"(제\d+조(?:의\d+)?(?: 제\d+항)?)", article)
    return m.group(1)


def group_lines(rows):
    """같은 조·항 청크들을 줄 집합으로 합친다(머리줄·첫 줄 중복 제거). 호 묶음 경계가 달라
    청크 순서가 달라도 같은 줄이면 같게 보려고 정렬한다(줄마다 호 번호가 있어 순서 정보는 남는다)."""
    return "\n".join(sorted({line for t in rows for line in t.split("\n")}))


def main():
    con = sqlite3.connect(P.KB_DB)
    con.row_factory = sqlite3.Row
    ours = collections.OrderedDict()
    for r in con.execute("SELECT doc_id, article, text FROM kb_chunks WHERE status='CURRENT' ORDER BY chunk_id"):
        ours[(r["doc_id"], r["article"])] = r["text"]
    src_name = con.execute("SELECT v FROM build_info WHERE k='source'").fetchone()[0]
    for d in COMPARE_VERSION:
        p = P.DATA / src_name / f"doc{d:02d}" / "대조용_묶음버전" / "response.json"
        name = con.execute("SELECT law_name FROM kb_documents WHERE doc_id=?", (d,)).fetchone()[0]
        for k in [k for k in ours if k[0] == d]:
            del ours[k]
        for c in bd.build_units(bd.articles_of(json.loads(p.read_text()), "law"), bd.norm(name), ""):
            if c["status"] == "CURRENT":
                ours[(d, c["article"])] = c["text"]
    bundle = [json.loads(l) for f in FILES for l in open(BUNDLE / f, encoding="utf-8")]
    # 남는 차이의 이유를 가리기 위한 다른 버전 글
    alt = collections.defaultdict(list)   # (doc, 조·항) → [(이름, 줄 묶음 글)]
    for r in con.execute("SELECT doc_id, article, text, status, effective_from FROM kb_chunks "
                         "WHERE status IN ('CURRENT','PENDING') ORDER BY chunk_id"):
        if r["status"] == "CURRENT" and r["doc_id"] not in COMPARE_VERSION:
            continue
        name = ("버전: 묶음 PDF 글이 DB 현행(더 나중 시행본)과 같음" if r["status"] == "CURRENT"
                else f"버전: 묶음 PDF 글이 시행 예정본({r['effective_from']})과 같음")
        alt[(r["doc_id"], unit_of(r["article"]), name)].append(r["text"])
    archived_units = {(r[0], unit_of(r[1])) for r in con.execute(
        "SELECT doc_id, article FROM kb_chunks WHERE status='ARCHIVED'")}
    bundle_pending_units = {(json.loads(l)["doc_id"], unit_of(json.loads(l)["article"]))
                            for f in ("stage1/law_chunks_pending_archived.jsonl", "stage2/law2_chunks_pending_archived.jsonl")
                            for l in open(BUNDLE / f, encoding="utf-8")}

    table_fix_units = {(b["doc_id"], unit_of(b["article"])) for b in bundle if b.get("table_fix")}

    def explain(doc, unit, bundle_texts):
        if (doc, unit) in CHECKED:
            return CHECKED[(doc, unit)]
        if (doc, unit) in table_fix_units:
            return "표·계산식: 묶음은 PDF 그림을 사람이 손으로 옮김(table_fix), API는 상자 표·이미지"
        target = normalize_all(group_lines(bundle_texts))
        for (d, u, name), texts in sorted(alt.items()):
            if d == doc and u == unit and normalize_all(group_lines(texts)) == target:
                return name
        return "내용 차이"

    rows, per_doc = [], collections.defaultdict(collections.Counter)
    all_b = collections.defaultdict(list)
    for b in bundle:
        all_b[(b["doc_id"], unit_of(b["article"]))].append(b)
    ours_group = collections.defaultdict(list)
    for (d, art), t in ours.items():
        ours_group[(d, unit_of(art))].append(t)
    for k, bs in all_b.items():
        if all((b["doc_id"], b["article"]) in ours for b in bs):
            # 청크 경계가 같음 → 청크끼리 비교
            for b in bs:
                o = ours[(b["doc_id"], b["article"])]
                cat = classify(o, b["text"])
                if cat == "내용 차이":
                    cat = explain(k[0], k[1], [x["text"] for x in bs])
                per_doc[b["doc_id"]][cat] += 1
                if cat != "같음":
                    rows.append((b["doc_id"], b["article"], "청크", cat, snippet(o, b["text"])))
        elif k not in ours_group:
            cat = "DB에 없음: DB는 삭제 단위로 ARCHIVED" if k in archived_units else "DB에 없음"
            if cat == "DB에 없음":
                cat = explain(k[0], k[1], [b["text"] for b in bs])
                cat = "DB에 없음" if cat == "내용 차이" else cat
            per_doc[k[0]][cat] += len(bs)
            rows.append((k[0], k[1], "조·항", cat, bs[0]["text"][:80].replace("\n", " ")))
        else:
            # 호 묶음 경계가 다름 → 같은 조·항 전체를 줄 단위로 비교
            cat = classify(group_lines(ours_group[k]), group_lines([b["text"] for b in bs]))
            if cat == "내용 차이":
                cat = explain(k[0], k[1], [b["text"] for b in bs])
            per_doc[k[0]]["경계 다름·" + cat] += len(bs)
            if cat != "같음":
                rows.append((k[0], k[1], "조·항", cat,
                             snippet(group_lines(ours_group[k]), group_lines([b["text"] for b in bs]))))
    # 우리 쪽에만 있는 조·항(묶음에 없음)
    bundle_units = {(b["doc_id"], unit_of(b["article"])) for b in bundle}
    only_ours = collections.Counter()
    for k in ours_group:
        if k not in bundle_units:
            cat = ("묶음에 없음: 묶음은 시행 예정본(PENDING)으로만 둠" if k in bundle_pending_units
                   else "묶음에 없음")
            only_ours[f"{k[0]} {cat}"] += 1
            rows.append((k[0], k[1], "조·항", cat, ours_group[k][0][:80].replace("\n", " ")))

    out = P.DB_OUT / "대조결과"
    out.mkdir(parents=True, exist_ok=True)
    summary = {
        "비교_버전": "doc 2·18·19는 대조용_묶음버전 본문, 나머지는 DB 현행",
        "묶음_청크_수": len(bundle),
        "문서별": {str(d): dict(sorted(per_doc[d].items())) for d in sorted(per_doc)},
        "DB에만_있는_조항_수": dict(sorted(only_ours.items())),
    }
    (out / "요약.json").write_text(json.dumps(summary, ensure_ascii=False, indent=1) + "\n")
    with open(out / "차이목록.tsv", "w", encoding="utf-8") as f:
        f.write("doc_id\t단위\t비교\t분류\t차이(앞 2곳)\n")
        for r in sorted(rows, key=lambda r: (r[0], r[1])):
            f.write("\t".join(str(x).replace("\t", " ") for x in r) + "\n")
    tot = collections.Counter()
    for d in per_doc.values():
        tot.update(d)
    print(json.dumps(dict(tot.most_common()), ensure_ascii=False))


if __name__ == "__main__":
    main()
