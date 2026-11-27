#!/usr/bin/env python3
"""2단계 다-1: 문서 50건 사용 정책 검토표(data/out/db/사용정책_검토표.md, .tsv)를 만든다.

근거 칸은 원본 파일에서 읽어 채우고, 제안 칸은 아래 PROPOSAL 표(사람이 읽을 수 있게 코드에 적은 판단)에서 온다.
DB의 use_policy는 바꾸지 않는다(다-2는 사용자 결정 뒤).
train은 숫자만 센다(문장은 읽거나 옮기지 않는다). valid·SKT 59건·test·정답사양서는 열지 않는다.
실행: python3 db/make_policy_review.py
"""
from __future__ import annotations

import collections
import csv
import json
import re
import sqlite3
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

import sys
HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))  # build/: paths.py
import paths as P  # noqa: E402
DB = P.KB_DB
REG = P.REGISTRY_1116
SRC = P.KB_SOURCES_CURRENT
BUNDLE = P.BUNDLE
OUT_MD, OUT_TSV = P.DB_OUT / "사용정책_검토표.md", P.DB_OUT / "사용정책_검토표.tsv"

# 즉답처리기준서 v1.1 5장(옛 기준, 비교용). 원문 표의 행을 문서에 대응시킨 것
CH5 = {**{d: "1단계 법령: 3장 규칙이 참조하는 조문과 관련 조문" for d in (1, 2, 5, 6)},
       3: "1단계 법령: 3장 규칙이 참조하는 조문과 관련 조문", 4: "1단계 법령: 3장 규칙이 참조하는 조문과 관련 조문",
       16: "2단계 '관련 고시'", 17: "2단계 '관련 고시'",
       22: "범위 밖(신용정보법)", 23: "범위 밖(신용정보법)",
       29: "범위 밖(노동관계법)", 30: "범위 밖(노동관계법)", 31: "범위 밖(노동관계법)",
       13: "1단계 가이드 3종에 있음", 9: "1단계 가이드 3종에 있음",
       14: "1단계 3종에 없음. 2단계 '자동화된 결정 관련 안내서'에 해당",
       48: "2단계 '국외 이전 관련 안내서'에 해당할 수 있음(관련 규칙 R-M01·R-P02)"}
for d in (7, 8, 10, 11, 12, 15):
    CH5[d] = "1단계 3종에 없음"

# 쟁점 핵심 조문의 법 이름 → 법률 doc
ISSUE_LAW = {"개인정보 보호법": 1, "정보통신망법": 3, "위치정보법": 5, "통신비밀보호법": 20, "신용정보법": 22,
             "근로자참여법": 31}
TEAM_ISSUES = {"P24", "P25", "P26", "P27"}  # issue_articles.json 중 팀이 더한 4개(영상·통신비밀·신용정보·임직원)
GOLD_LAW = {"법": 1, "시행령": 2, "망법": 3, "망법 시행령": 4, "위치법": 5, "위치법 시행령": 6}
OOS_LAW = {"통신비밀보호법": 20, "신용정보법": 22, "근로기준법": 29, "전기통신사업법": 18, "전자상거래법": 24,
           "국세기본법": 27}

A, B = "ANSWER_BASIS", "REFERENCE"
DEC = "결정 필요"
# 제안: (정책, 쓰는 범위 안, 한 줄 이유). 범위의 {A1}…는 아래에서 조 접두 목록으로 바꾼다.
P1 = ("1단계 법령은 등록목록 v1.2 1단계(즉답 핵심 근거). 5장(1) '규칙 조문과 관련 조문'만 즉답 근거는 v1.2가 다시 "
      "정하지 않아 그대로 씀 → 안A(규칙+관련)가 기본안. 안B(+규칙 밖 개인정보 조문)는 팀 추천. {cov}")
GROUP_G = ("원본 related=GENERAL(개발가이드 v1.2: '일반 질의 근거')로 등록했지만 2단계는 '특정 쟁점의 보완 근거'이고, "
           "이 문서와 이어지는 관련 규칙 ID·쟁점이 없음. 원본이 즉답 범위를 말하지 않아 사람이 정함")
PROPOSAL = {
    1: (A, "안A {A1} / 안B {B1} — 결정 필요(안A·안B)", P1),
    2: (A, "안A {A2} / 안B {B2} — 결정 필요(안A·안B)", P1),
    3: (A, "안A {A3} / 안B {B3} — 결정 필요(안A·안B)", P1),
    4: (A, "안A {A4} / 안B {B4} — 결정 필요(안A·안B)", P1),
    5: (A, "안A {A5} / 안B {B5} — 결정 필요(안A·안B)", P1),
    6: (A, "안A {A6} / 안B {B6} — 결정 필요(안A·안B)", P1),
    7: (DEC, "문서 전체({root7})", "REFERENCE 후보. 등록목록 1단계(즉답 핵심 근거)이지만 같은 행 비고가 '유출 의심 문의는 규칙상 "
        "이관 — 안내 근거로만 사용'. 팀 1단계 verified=1과도 다름"),
    8: (A, "문서 전체({root8})", "등록목록 1단계 안내서(기본안). 비고 없음"),
    9: (A, "문서 전체({root9})", "등록목록 1단계 안내서, 관련 규칙 R-M02. 5장 옛 목록에도 있음"),
    10: (A, "문서 전체({root10})", "등록목록 1단계 안내서. 비고 '즉답 문체·범위 기준으로도 활용'은 쓰임을 더한 것이라 줄이지 않음. "
         "설계서 8장의 '사례집·FAQ는 등록하지 않음'은 2단계 칸 글이고, 같은 8장 1단계 칸이 이 모음집을 이름으로 적음"),
    11: (A, "문서 전체({root11})", "등록목록 1단계 안내서(기본안). 비고 없음"),
    12: (A, "문서 전체({root12})", "등록목록 1단계 안내서(기본안). 비고 없음"),
    13: (A, "문서 전체({root13})", "등록목록 1단계, 비고 '즉답 근거의 중심 문서', 관련 규칙 R-B01·R-B02·R-P01·R-P03·R-P04. 5장 옛 목록에도 있음"),
    14: (A, "문서 전체({root14})", "등록목록 1단계 안내서, 관련 규칙 R-M03(자동화된 결정). 5장은 2단계로 두었으나 v1.2가 1단계로 올림"),
    15: (A, "문서 전체({root15})", "등록목록 1단계 안내서(기본안). 비고 없음"),
    16: (A, DEC + ": 전체 {all16} / 범위 글 주제(접근통제·암호화·접속기록) 조문", "등록목록 2단계 '고시'(보완 근거), 5장 2단계 '관련 고시', "
         "related GENERAL, 팀 1이 모두 같은 쪽. 범위 글에 조문 번호가 없어 쓰는 범위만 사람이 정함. 보호법·시행령에서 고시로 가는 위임 연결 {deleg}개"),
    17: (A, DEC + ": 전체 {all17} / 일부", "16과 같음. 범위 글 '처리 방법 세부 기준'에 조문 번호 없음"),
    18: (DEC, DEC + "(범위 글 '이용자 정보 제공(통신이용자정보) 관련 조문'에 조문 번호 없음)",
         "등록목록 2단계 '통신 관련 조문'(보완 근거), related GENERAL. 그러나 쓰는 범위가 흐림. 팀 정답은 train 범위 밖 법령 {oos18}건, 팀 추천 0"),
    19: (DEC, DEC + "(범위 글 '위 법률 위임 사항'. 18이 정해져야 위임 연결로 뽑을 수 있음)", "18과 같음"),
    20: (DEC, "쟁점 P25 핵심 조문 {S20}(위임 연결로 한 단계 닿는 조문 없음)",
         "현업 원본에는 범위 밖이라는 글이 없음(related GENERAL, 등록목록 2단계 '통신 관련 조문'). 팀 쟁점 P25만 kb_out_note=true(항상 검토이관). "
         "팀 정답 train 범위 밖 {oos20}건, 팀 추천 0"),
    21: (DEC, "없음(P25 핵심 조문에서 시행령으로 가는 위임 연결 0)", "20과 같음. 범위 글 '보관 기간 등'에 조문 번호 없음"),
    22: (B, "보여 줄 조문 {S22}(쟁점 P26 핵심 조문)", "kb_sources REFERENCE_ONLY, 등록목록 비고 '즉답 근거로 지정하지 않음(5장 범위 밖)', "
         "개발가이드 표 등록(REFERENCE_ONLY). 조문은 왜 넘기는지 보여 주는 데만 씀"),
    23: (B, "보여 줄 조문 {S23}(P26 핵심 조문에서 위임 한 단계)", "22와 같음"),
    24: (A, "{S24}(시행령 제6조로 위임하는 법 제6조 — 위임 한 단계)", "등록목록 2단계 '보존기간 관련 조문'(보완 근거), related GENERAL, 범위 밖 목록에 없음. "
         "범위 글 '거래기록 보존기간'에 번호가 없어 시행령 제6조에서 위임 연결로 뽑음. 팀과 다름: 추천 0, 정답 train 범위 밖 {oos24}건"),
    25: (A, "{S25}(범위 글 '보존 대상·기간(제6조)')", "등록목록 2단계 '보존기간 관련 조문', related GENERAL. 팀과 다름: 추천 0"),
    26: (A, "{S26}(범위 글 '상업장부·서류 보존(제33조)')", "등록목록 2단계 '보존기간 관련 조문', related GENERAL. 제33조의 위임은 KB 밖(상법 시행령). "
         "팀과 다름: 추천 '적재하지 않음'"),
    27: (A, "{S27}(범위 글 '장부·증빙 보존(제85조의3)')", "등록목록 2단계 '보존기간 관련 조문', related GENERAL. 팀과 다름: 추천 0, 정답 train 범위 밖 {oos27}건"),
    28: (A, "{S28}(법 제85조의3에서 위임 한 단계)", "27과 같음. 범위 글 '위 법률 위임 사항'"),
    29: (B, "보여 줄 조문 {S29}(범위 글 '제41조·제42조')", "kb_sources REFERENCE_ONLY, 등록목록 비고 '즉답 근거로 지정하지 않음(5장 범위 밖)'"),
    30: (B, "보여 줄 조문 {S30}(법 제41조·제42조에서 위임 한 단계)", "29와 같음"),
    31: (B, "보여 줄 조문 {S31}(범위 글 '제20조', 쟁점 P27)", "29와 같음. P27은 팀 쟁점이고 kb_out_note=true"),
    32: (DEC, DEC + "(범위 글 '주민등록번호 처리 관련'에 조문 번호 없음)", "등록목록 2단계, related GENERAL. 쓰는 범위가 흐림. 팀 추천 0, train 0"),
    33: (DEC, DEC + "(범위 글 'AI 서비스 고지·투명성(개인정보 연계 문의)'에 조문 번호 없음)", "등록목록 2단계, related GENERAL. 쓰는 범위가 흐림. 팀 추천 0, train 0"),
    34: (DEC, DEC + "(범위 글 '위 법률 위임 사항')", "33과 같음"),
    35: (DEC, "문서 전체({root35})", GROUP_G),
    36: (DEC, "문서 전체({root36})", GROUP_G),
    37: (DEC, "문서 전체({root37})", GROUP_G),
    38: (DEC, "문서 전체({root38})", GROUP_G),
    39: (DEC, "문서 전체({root39})", GROUP_G),
    40: (A, "문서 전체({root40})", "쟁점 P24(영상정보처리기기) 핵심 조문(보호법 제25조·제25조의2)을 인용한 청크가 많음. 단 P24는 팀이 더한 쟁점이고 "
         "원본 related는 GENERAL뿐. 팀 1"),
    41: (A, "문서 전체({root41})", "관련 규칙 R-D01(민감정보, 보호법 제23조). 팀 1"),
    42: (DEC, "일부안: {P42}", "팀 추천 '일부만 1'. 근거: 등록목록 제외 기준 '다른 업종·공공기관 대상'과 보류 기준 '의료', 5장 범위 밖(의료법·노동관계법)을 편 단위에 "
         "적용(편별 숫자는 보고서 4절). 편 단위 적용은 이 표의 해석이라 사람이 정함"),
    43: (A, "문서 전체({root43})", "관련 규칙 R-D03(아동, 보호법 제22조의2). 팀 1"),
    44: (DEC, "문서 전체({root44})", GROUP_G),
    45: (DEC, "문서 전체({root45})", GROUP_G + ". 핵심 조문 인용은 범위 밖 쟁점(P25·P26)이 가장 많음"),
    46: (DEC, "문서 전체({root46})", GROUP_G),
    47: (A, "문서 전체({root47})", "40과 같음(P24 핵심 조문 인용 청크가 많음, P24는 팀 쟁점, 원본 related는 GENERAL). 팀 1"),
    48: (A, "문서 전체({root48})", "관련 규칙 R-M01·R-P02(국외 이전). 팀 1"),
    49: (DEC, "문서 전체({root49})", GROUP_G),
    50: (DEC, "문서 전체({root50})", GROUP_G),
}
# 분야별 안내서 편 단위 안(제목 → 쓰는지, 왜)
PARTS_42 = {
    "인사·노무 편": (DEC, "모든 사업자에 해당. 그러나 노동관계법(5장 범위 밖) 인용 청크 있음, 팀 쟁점 P27 kb_out_note"),
    "사회복지시설 편": ("빼기", "등록목록 제외 기준 '다른 업종 대상'"),
    "의료기관 편": ("빼기", "의료법(5장 범위 밖) 인용 청크 많음, 등록목록 보류 '의료'"),
    "약국 편": ("빼기", "등록목록 보류 '의료', 다른 업종"),
    "학원·교습소 편": ("빼기", "등록목록 제외 기준 '다른 업종 대상'"),
    "통계작성 편": ("쓰기", "업종이 아니라 처리 유형(통계 작성)"),
    "공공기관 편": ("빼기", "등록목록 제외 기준 '공공기관 대상'"),
    "온라인 경품행사 편": ("쓰기", "업종이 아니라 처리 유형(경품행사)"),
}


def xlsx_rows(path: Path):
    ns = {"m": "http://schemas.openxmlformats.org/spreadsheetml/2006/main",
          "r": "http://schemas.openxmlformats.org/officeDocument/2006/relationships"}
    z = zipfile.ZipFile(path)
    ss = ["".join(t.text or "" for t in si.iter("{%s}t" % ns["m"]))
          for si in ET.fromstring(z.read("xl/sharedStrings.xml")).findall("m:si", ns)] \
        if "xl/sharedStrings.xml" in z.namelist() else []
    rels = {r.get("Id"): r.get("Target") for r in ET.fromstring(z.read("xl/_rels/workbook.xml.rels"))}
    out = {}
    for sh in ET.fromstring(z.read("xl/workbook.xml")).find("m:sheets", ns):
        tgt = rels[sh.get("{%s}id" % ns["r"])].lstrip("/").replace("xl/", "")
        rows = []
        for row in ET.fromstring(z.read("xl/" + tgt)).iter("{%s}row" % ns["m"]):
            cells = {}
            for cc in row.findall("m:c", ns):
                col = re.match(r"[A-Z]+", cc.get("r")).group(0)
                v = cc.find("m:v", ns)
                if cc.get("t") == "s" and v is not None:
                    cells[col] = ss[int(v.text)]
                elif cc.get("t") == "inlineStr":
                    cells[col] = "".join(t.text or "" for t in cc.iter("{%s}t" % ns["m"]))
                else:
                    cells[col] = v.text if v is not None else ""
            rows.append(cells)
        out[sh.get("name")] = rows
    return out


def art_key(jo, sub):
    return f"{jo}-{sub}" if sub else str(jo)


def main():
    con = sqlite3.connect(DB)
    docs = {r[0]: r for r in con.execute("SELECT doc_id, title, law_name, law_kind, stage, source_type, use_policy "
                                         "FROM kb_documents ORDER BY doc_id")}
    keyname = {d: re.sub(r"\s+", "", r[2]) for d, r in docs.items()}
    roots = {d: s for d, s in con.execute("SELECT doc_id, section_id FROM guide_sections WHERE parent_id IS NULL")}

    reg = {int(r["A"]): r for r in xlsx_rows(REG)["등록목록"][1:] if r.get("A", "").isdigit() and r.get("B") in ("1단계", "2단계")}
    srcs = list(csv.DictReader(SRC.open(encoding="utf-8-sig")))[:50]   # 등록목록 No 1~50과 같은 순서
    for i, s in enumerate(srcs, 1):
        assert re.sub(r"\s", "", s["title"])[:6] in re.sub(r"\s", "", reg[i]["D"]) or i in (3, 4, 5, 6, 20, 21, 22, 23, 24, 25, 31, 33, 34), (i, s["title"])
    team2 = {int(r["No"]): r["제안"] for r in csv.DictReader((BUNDLE / "verified/2단계_문서별_추천안.csv").open(encoding="utf-8-sig"))}

    # 1단계 법령 조문 묶음(팀 표) → 안A(규칙+관련), 안B(+규칙 밖 개인정보 조문)
    grp = collections.defaultdict(lambda: collections.defaultdict(set))
    for r in csv.DictReader((BUNDLE / "verified/1단계법령_조문묶음.csv").open(encoding="utf-8-sig")):
        for a in filter(None, (x.strip() for x in r["조문(현행 청크가 있는 조)"].split(","))):
            m = re.fullmatch(r"제(\d+)조(?:의(\d+))?", a)
            grp[int(r["No"])][r["묶음"]].add((int(m.group(1)), int(m.group(2) or 0)))
    planA = {d: grp[d]["규칙 조문"] | grp[d]["관련 조문"] for d in range(1, 7)}
    planB = {d: planA[d] | grp[d]["규칙 밖 개인정보 조문"] for d in range(1, 7)}

    # 쟁점 핵심 조문
    issues = json.loads((BUNDLE / "issue/issue_articles.json").read_text())
    iss_doc = collections.defaultdict(lambda: collections.defaultdict(set))   # doc → 조 → {쟁점}
    kbout = collections.defaultdict(set)
    for x in issues:
        for a in x["articles"]:
            m = re.match(r"(.+?) 제(\d+)조(?:의(\d+))?", a)
            d = ISSUE_LAW[m.group(1)]
            iss_doc[d][(int(m.group(2)), int(m.group(3) or 0))].add(x["id"])
            if x["kb_out_note"]:
                kbout[d].add(x["id"])

    # train: 숫자만 센다
    train = json.loads((BUNDLE / "eval/cases_train_v2.json").read_text())
    gold = collections.Counter()
    gold_cases = gold_n = 0
    cov = {"A": [0, 0], "B": [0, 0]}
    for x in train:
        g = x["gold_articles"] or []
        if x["intake_type"] != "GENERAL" or not g:
            continue
        gold_n += 1
        okA, okB = [], []
        for a in g:
            m = re.fullmatch(r"(.+?) 제(\d+)조(?:의(\d+))?", a)
            d, ja = GOLD_LAW[m.group(1)], (int(m.group(2)), int(m.group(3) or 0))
            gold[d] += 1
            okA.append(ja in planA[d])
            okB.append(ja in planB[d])
        cov["A"][0] += sum(okA); cov["B"][0] += sum(okB)
        cov["A"][1] += all(okA); cov["B"][1] += all(okB)
    gtot = sum(gold.values())
    oos = collections.defaultdict(lambda: [0, 0])
    for x in train:
        if x["out_of_scope_law"] in OOS_LAW:
            oos[OOS_LAW[x["out_of_scope_law"]]][0 if x["intake_type"] == "SPECIFIC" else 1] += 1
    covtxt = (f"train 일반 문의 정답 조문 {gtot}개 중 범위 안: 안A {cov['A'][0]}개({cov['A'][0] / gtot:.1%}), "
              f"안B {cov['B'][0]}개({cov['B'][0] / gtot:.1%}). 정답이 모두 범위 안인 문의 {gold_n}건 중 안A {cov['A'][1]}, 안B {cov['B'][1]}")

    # 안내서: 쟁점 핵심 조문 인용 청크(대략. '법 제N조' 꼴만 셈)
    lawpat = {1: r"(?:보호법|개인정보 보호법」?|(?<![가-힣」])법)", 3: r"(?:정보통신망법|망법|정보통신망 이용촉진 및 정보보호 등에 관한 법률」?)",
              5: r"(?:위치정보법|위치정보의 보호 및 이용 등에 관한 법률」?)", 20: r"통신비밀보호법」?",
              22: r"(?:신용정보법|신용정보의 이용 및 보호에 관한 법률」?)", 31: r"(?:근로자참여법|근로자참여 및 협력증진에 관한 법률」?)"}
    short = {1: "보호법", 3: "망법", 5: "위치법", 20: "통비법", 22: "신정법", 31: "참여법"}
    core_pat = {}
    for d, arts in iss_doc.items():
        for (jo, sub) in arts:
            core_pat[(d, jo, sub)] = re.compile(lawpat[d] + rf"\s*제{jo}조" + (f"의{sub}" if sub else "") + r"(?!의\d)")
    guide_cites = {}
    for d in (x for x in docs if docs[x][5] == "GUIDE"):
        cnt = collections.Counter()
        any_n = 0
        for (t,) in con.execute("SELECT text FROM kb_chunks WHERE doc_id=?", (d,)):
            hit = [k for k, p in core_pat.items() if p.search(t)]
            any_n += bool(hit)
            cnt.update(hit)
        top = ", ".join(f"{short[k[0]]} 제{k[1]}조{'의' + str(k[2]) if k[2] else ''} {n}" for k, n in cnt.most_common(3))
        guide_cites[d] = f"{any_n}" + (f" ({top})" if top else "")

    def prefixes(d, arts):
        return [f"{keyname[d]}|{art_key(j, s)}|" for j, s in sorted(arts)]

    def check(d, arts):  # 접두가 DB 현행 조문에 닿는지
        for p in prefixes(d, arts):
            assert con.execute("SELECT 1 FROM kb_chunks WHERE unit_key LIKE ? AND status='CURRENT'", (p + "%",)).fetchone(), p
        return prefixes(d, arts)

    fill = {"cov": covtxt,
            "deleg": con.execute("SELECT count(*) FROM kb_links WHERE link_type='위임' AND dst_doc_id IN (16,17)").fetchone()[0]}
    for d in range(1, 7):
        fill[f"A{d}"] = f"{len(planA[d])}개 조"
        fill[f"B{d}"] = f"{len(planB[d])}개 조"
        check(d, planB[d])
    for d in (16, 17):
        n = con.execute("SELECT count(DISTINCT jo || '-' || jo_sub) FROM kb_chunks WHERE doc_id=? AND status='CURRENT'", (d,)).fetchone()[0]
        fill[f"all{d}"] = f"{n}개 조"
    seeds = {20: {(3, 0), (14, 0)}, 22: {(32, 0), (33, 0)}, 23: {(28, 0), (28, 2)}, 24: {(6, 0)}, 25: {(6, 0)},
             26: {(33, 0)}, 27: {(85, 3)}, 28: {(65, 7)}, 29: {(41, 0), (42, 0)}, 30: {(20, 0), (21, 0), (22, 0)},
             31: {(20, 0)}}
    scope = {}
    for d, arts in seeds.items():
        scope[d] = check(d, arts)
        fill[f"S{d}"] = " ".join(f"`{p}`" for p in scope[d])
    for d in docs:
        if d in roots:
            fill[f"root{d}"] = f"`{roots[d]}`"
    parts = {t: s for s, t in con.execute("SELECT section_id, title FROM guide_sections WHERE doc_id=42 AND depth=2")}
    fill["P42"] = "; ".join(f"{t} `{parts[t]}` {v[0]}" for t, v in PARTS_42.items())
    for o in (18, 20, 24, 27):
        fill[f"oos{o}"] = f"{sum(oos[o])}(구체 {oos[o][0]}·일반 {oos[o][1]})"

    cols = ["doc_id", "제목", "종류", "단계", "등록목록_단계", "등록목록_주요활용범위", "등록목록_비고", "kb_sources_related",
            "REFERENCE_ONLY", "기준서5장_옛기준(비교)", "팀_verified_추천(참고)", "쟁점_핵심조문(조수: 쟁점ID)", "kb_out_note",
            "train_gold_articles", "train_out_of_scope_law(구체·일반)", "안내서_핵심조문_인용청크(대략)", "제안_use_policy",
            "제안_use_scope", "이유"]
    rows = []
    for d in sorted(docs):
        _, title, _, kind, stage, stype, _ = docs[d]
        rg, sr = reg[d], srcs[d - 1]
        team = team2.get(d)
        if team is None:
            team = "조 단위: 규칙·관련·규칙 밖 개인정보 = 1, 먼 조문 = 0" if d <= 6 else "1(1단계 현행 verified 1, 팀 11/20 결정)"
        ia = iss_doc.get(d, {})
        iss_txt = f"{len(ia)}: " + ",".join(sorted({i for v in ia.values() for i in v})) if ia else "0"
        if ia and set().union(*ia.values()) & TEAM_ISSUES:
            iss_txt += "(팀 쟁점 포함)"
        gold_txt = str(gold.get(d, 0)) if d <= 6 else "0"
        if d in oos:
            oos_txt = f"{sum(oos[d])} ({oos[d][0]}·{oos[d][1]})"
        elif d in (19, 21, 23, 25, 28, 30):
            oos_txt = "법률 행에 셈"
        else:
            oos_txt = "0"
        pol, scp, why = PROPOSAL[d]
        rows.append([str(d), title, kind, str(stage), rg["B"], rg.get("G", ""), rg.get("K", ""), sr["related"],
                     "예" if sr["related"] == "REFERENCE_ONLY" else "", CH5.get(d, "언급 없음"), team, iss_txt,
                     ",".join(sorted(kbout[d])) if kbout.get(d) else "", gold_txt, oos_txt,
                     guide_cites.get(d, "") if stype == "GUIDE" else "", pol, scp.format(**fill), why.format(**fill)])

    with OUT_TSV.open("w", encoding="utf-8") as f:
        f.write("\t".join(cols) + "\n")
        for r in rows:
            f.write("\t".join(x.replace("\t", " ").replace("\n", " ") for x in r) + "\n")

    n = collections.Counter(r[16] for r in rows)
    md = [
        "# 사용 정책 검토표 (2단계 다-1, 2026-11-25)",
        "",
        "- 이 표는 **제안**이다. DB `use_policy`·`use_scope`는 아직 넣지 않았다(사용자 결정 뒤 다-2).",
        "- 기준 순위: 설계서·RAG 등록목록 v1.2(11/16)가 1순위. 기준서 v1.1 5장은 v1.2와 부딪히지 않는 곳만 쓰고, 5장 안내서 3종 목록은 비교 칸으로만 둔다. "
        "팀 자료(verified 추천안, 조문묶음, 팀 쟁점, train 정답)는 참고만 한다.",
        f"- 제안 수: {', '.join(f'{k} {v}' for k, v in sorted(n.items()))} (50행). 만든 스크립트: `db/make_policy_review.py`. 같은 내용 TSV: `사용정책_검토표.tsv`(칸 전부).",
        "",
        "## 제안을 정한 방법 (무엇을 보고 정하나)",
        "1. kb_sources `related=REFERENCE_ONLY`(= 등록목록 비고 '즉답 근거로 지정하지 않음') → `REFERENCE`. 쓰는 범위 칸은 '왜 넘기는지' 보여 줄 조문.",
        "2. 1단계 법령(1~6) → `ANSWER_BASIS`. 쓰는 범위는 5장 '3장 규칙이 참조하는 조문과 관련 조문' = 조문묶음의 규칙+관련(안A). 팀 추천인 안B(+규칙 밖 개인정보 조문)를 나란히 둔다.",
        "3. 그 밖의 법령: 등록목록 2단계(보완 근거)이고 범위 밖이 아니면, 범위 글에 적힌 조문 + 쟁점 핵심 조문 + 그 조문에서 위임 연결(D4)로 한 단계 닿는 조문을 쓰는 범위로 하고 `ANSWER_BASIS`. "
        "범위 글에 조문 번호가 없으면 `결정 필요`.",
        "4. 1단계 안내서(7~15) → `ANSWER_BASIS`(등록목록 1단계 기본안). 같은 행 비고가 다른 쓰임을 적으면 그것을 따른다(7: 결정 필요).",
        "5. 2단계 안내서(35~50): 관련 규칙 ID가 있거나 쟁점 핵심 조문으로 이어지면 `ANSWER_BASIS`, `GENERAL`만 있고 이어지는 쟁점이 없으면 `결정 필요`.",
        "6. 팀 자료와 다른 제안은 이유 칸에 '팀과 다름'으로 적었다.",
        "",
        "## 칸 설명",
        "- 쟁점 핵심 조문: `issue_articles.json`에서 그 법(시행령 아님)의 조 수와 쟁점 ID. P24~P27은 팀이 더한 쟁점.",
        "- train gold: train 일반 문의 89건(정답 있는 77건)의 `gold_articles` 개수(문서별). train 범위 밖: `out_of_scope_law`가 그 법인 문의 수(구체·일반). 문장은 옮기지 않았다.",
        "- 안내서 핵심 조문 인용 청크: 청크 본문에 '법 제25조' 꼴로 쟁점 핵심 조문이 나오는 청크 수와 많은 순 3개. 낱말 맞추기라 대략 값이다.",
        "- 쓰는 범위: 법령은 `unit_key` 조 접두(예 `개인정보보호법|15|`), 안내서는 `guide_sections.section_id`(뿌리 = 문서 전체). 모든 접두가 DB 현행 조문에 닿는 것을 확인했다.",
        "",
        "## 표",
        "| doc | 제목 | 종류·단계 | 등록목록 v1.2 (단계 / 주요 활용 범위 / 비고) | related | 5장 옛 기준(비교) | 팀 추천(참고) | 쟁점 핵심 조문 | train gold / 범위 밖 | 안내서 핵심 조문 인용 | **제안** | 쓰는 범위 | 이유 |",
        "|---|---|---|---|---|---|---|---|---|---|---|---|---|",
    ]
    for r in rows:
        reg_txt = " / ".join(x for x in (r[4], r[5], r[6]) if x)
        iss = r[11] + (f" kb_out:{r[12]}" if r[12] else "")
        md.append("| " + " | ".join(x.replace("|", "\\|") for x in [
            r[0], r[1], f"{r[2]}·{r[3]}", reg_txt, r[7], r[9], r[10], iss, f"{r[13]} / {r[14]}", r[15], f"**{r[16]}**",
            r[17], r[18]]) + " |")
    md += ["",
           "## 표 밖 (등록 대상 아님)",
           "- 불법 스팸 방지를 위한 정보통신망법 안내서: 5장 옛 1단계 목록에만 있고, 등록목록 v1.2(100행)와 `backend/data/kb_raw/`에 없다. 최신본 기준 대상이 아니다. "
           f"참고로 광고성 정보 쟁점이 `expected.issues`에 있는 train 구체 문의는 {sum(1 for x in train if x['intake_type'] == 'SPECIFIC' and '광고성' in json.dumps((x.get('expected') or {}).get('issues', []), ensure_ascii=False))}건이다."]
    OUT_MD.write_text("\n".join(md) + "\n", encoding="utf-8")
    print(dict(n), covtxt)


if __name__ == "__main__":
    main()
