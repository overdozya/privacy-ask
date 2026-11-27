#!/usr/bin/env python3
"""쟁점 꾸러미 v1 측정과 사용자 검토표.

입력(읽기만): data/out/db/privacyask.db(build_db.py가 만든 것), data/수집/꾸러미_v1.json,
  현업 seed(issue_catalog.json, rule_set_v1.0.json), 확정 묶음 issue/issue_articles.json(팀 P3),
  eval/issue_core_articles.json(팀 핵심 조문), eval/cases_train_v2.json(train: gold_articles와 expected.issues의
  개수만 센다. 문의 문장은 읽지도 옮기지도 않는다). valid·SKT 59건·test는 열지 않는다.
출력: data/out/db/꾸러미_검토표.md(사용자가 이것만 보고 고칠 수 있게), 꾸러미_측정.json(숫자).
실행: python3 db/make_package_review.py [--version v2]  (기본 = DB의 지금 판. v1은 꾸러미_검토표.md·꾸러미_측정.json,
  다른 버전은 이름 끝에 _버전을 붙인 파일로 쓴다. v1 파일은 3단계 기록이라 그대로 남는다)
"""
from __future__ import annotations

import argparse
import collections
import json
import re
import sqlite3
from pathlib import Path

import sys
HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))  # build/: paths.py
import paths as P  # noqa: E402
DB = P.KB_DB
SEED = P.SEED_1116
BUNDLE = P.BUNDLE

# train gold_articles의 법령 약칭 → kb_documents.doc_id
GOLD_LAW = {"법": 1, "시행령": 2, "망법": 3, "망법 시행령": 4, "위치법": 5, "위치법 시행령": 6}

# 현업 설계서·개발가이드 v1.2에서 찾은, 팀 쟁점에만 있는 4가지 상황의 처리(완료 기준 4).
# 근거 위치는 docs/PrivacyAsk_산출물_20261116/ 아래 HTML. 꾸러미는 만들지 않는다(사용자 결정).
TEAM_ONLY = [
    ("신용정보", "범위 밖 → 검토이관(KB 밖). 즉답처리기준서 5장 '범위 밖'에 신용정보법이 있고, 개발가이드 KB 표는 신용정보법·시행령을 REFERENCE_ONLY('KB에 등록하되 즉답 근거로 지정하지 않음 — 필요한 문의는 검토이관')로 둔다. 쟁점·슬롯·규칙 없음"),
    ("임직원 개인정보", "노동관계법이 필요하면 범위 밖 → 검토이관(KB 밖). 즉답처리기준서 5장 '범위 밖'에 노동관계법, 개발가이드는 근로기준법(제41·42조)·근로자참여법(제20조)을 REFERENCE_ONLY로 둔다. '임직원' 쟁점·슬롯은 없고, 보호법 쪽 판단은 일반 쟁점(수집·이용 근거 등)으로 간다"),
    ("영상정보처리기기", "쟁점·슬롯·규칙 없음. 개발가이드 KB 표는 고정형·이동형 영상정보처리기기 안내서를 2단계 related=GENERAL('일반 질의 근거')로만 둔다. 즉 일반 질의 답변 근거로 쓰고 구체 문의의 쟁점으로는 세우지 않는다. 범위 밖 목록에는 없다"),
    ("통신비밀", "쟁점·슬롯·규칙 없음. 개발가이드 KB 표는 통신비밀보호법·시행령을 2단계 related=GENERAL로 두고 범위 밖 목록에도 넣지 않았다. 우리 사용 정책(2026-11-26)은 두 문서를 REFERENCE로 정했으므로, 필요한 문의는 즉답 근거가 없어 KB 밖 → 검토이관이 된다"),
]


def jo_key(s):
    """'제28조의8' → '28-8'"""
    m = re.fullmatch(r"제(\d+)조(?:의(\d+))?", s)
    return m.group(1) + (f"-{m.group(2)}" if m.group(2) else "")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--version")
    con = sqlite3.connect(DB)
    con.row_factory = sqlite3.Row
    ver = ap.parse_args().version or con.execute("SELECT v FROM build_info WHERE k='package_current'").fetchone()[0]
    files = json.loads(con.execute("SELECT v FROM build_info WHERE k='package_files'").fetchone()[0])
    spec = json.loads((P.DATA / files[ver]["file"]).read_text(encoding="utf-8"))
    suffix = "" if ver == "v1" else f"_{ver}"
    OUT_MD, OUT_JSON = P.DB_OUT / f"꾸러미_검토표{suffix}.md", P.DB_OUT / f"꾸러미_측정{suffix}.json"
    catalog = {c["issue_name"]: c for c in json.loads((SEED / "issue_catalog.json").read_text(encoding="utf-8"))}
    rules = {r["rule_id"]: r for r in json.loads((SEED / "rule_set_v1.0.json").read_text(encoding="utf-8"))["risk_rules"]}
    p3 = json.loads((BUNDLE / "issue" / "issue_articles.json").read_text(encoding="utf-8"))
    core = json.loads((BUNDLE / "eval" / "issue_core_articles.json").read_text(encoding="utf-8"))
    train = json.loads((BUNDLE / "eval" / "cases_train_v2.json").read_text(encoding="utf-8"))

    docs = {r["doc_id"]: r for r in con.execute("SELECT * FROM kb_documents")}
    name_to_doc = {r["law_name"]: d for d, r in docs.items() if r["source_type"] == "LAW"}
    pk = {r["package_id"]: r for r in con.execute("SELECT * FROM issue_packages WHERE version=? ORDER BY package_id", (ver,))}
    items = collections.defaultdict(list)
    for r in con.execute("SELECT * FROM package_items WHERE version=? ORDER BY package_id, ord", (ver,)):
        items[r["package_id"]].append(r)
    units = collections.defaultdict(list)
    for r in con.execute("SELECT u.*, c.doc_id, c.jo, c.jo_sub, length(c.text) n, a.doc_id adoc, "
                         "length(coalesce(a.text_rows,a.text)) an FROM package_item_units u "
                         "JOIN package_items i ON i.item_id=u.item_id AND i.version=? "
                         "LEFT JOIN kb_chunks c ON c.chunk_id=u.chunk_id LEFT JOIN kb_annexes a ON a.annex_id=u.annex_id "
                         "ORDER BY u.item_id, u.ord", (ver,)):
        units[r["item_id"]].append(r)
    by_issue = {r["issue_name"]: pid for pid, r in pk.items()}

    def label(it):
        k, kind = it["target_key"], it["target_kind"]
        d = docs[it["target_doc_id"]]
        if kind == "LAW_UNIT":
            if k.count("|") == 2:
                jo = k.split("|")[1].split("-")
                return f"{d['law_name']} 제{jo[0]}조" + (f"의{jo[1]}" if len(jo) > 1 else "") + " (조 전체)"
            a = con.execute("SELECT article FROM kb_chunks WHERE status='CURRENT' AND unit_key=?", (k,)).fetchone()[0]
            return f"{d['law_name']} {a}"
        if kind == "ANNEX":
            a = con.execute("SELECT label FROM kb_annexes WHERE status='CURRENT' AND annex_key=?", (k,)).fetchone()[0]
            return f"{d['law_name']} {a}"
        s = con.execute("SELECT num, title FROM guide_sections WHERE section_id=?", (k,)).fetchone()
        return f"[{d['doc_id']}] {d['title']} › {s['num']} {s['title'][:60]}"

    # 꾸러미별 조 집합(법령 조문 단위)과 핵심 조
    pkg_jo, pkg_core_jo, pkg_unit_keys = {}, {}, {}
    for pid in pk:
        js, cs, ks = set(), set(), set()
        for it in items[pid]:
            for u in units[it["item_id"]]:
                ks.add(u["unit_key"])
                if it["target_kind"] == "LAW_UNIT":
                    j = (u["doc_id"], u["jo"], u["jo_sub"] or 0)
                    js.add(j)
                    if it["role"] == "핵심":
                        cs.add(j)
        pkg_jo[pid], pkg_core_jo[pid], pkg_unit_keys[pid] = js, cs, ks

    # 1) 팀 P3·핵심 조문 포함률: 인용 표기 → 현행 청크 unit_key(호로 나뉜 항은 그 항의 청크 전부)
    def cite_keys(cite):
        for name, d in sorted(name_to_doc.items(), key=lambda x: -len(x[0])):
            if cite.startswith(name + " "):
                art = cite[len(name) + 1:]
                rows = [r[0] for r in con.execute(
                    "SELECT unit_key FROM kb_chunks WHERE status='CURRENT' AND doc_id=? AND (article=? OR article LIKE ?)",
                    (d, art, art + " %"))]
                return d, rows
        return None, []

    def coverage(pairs):
        res, miss = [], []
        for issue, cite in pairs:
            pid = by_issue.get(issue)
            d, ks = cite_keys(cite)
            ok = bool(pid and ks and all(k in pkg_unit_keys[pid] for k in ks))
            res.append(ok)
            if not ok:
                miss.append(dict(issue=issue, cite=cite, package=pid, in_db=bool(ks)))
        return sum(res), len(res), miss

    p3_pairs = [(x["issue"], a) for x in p3 if x["issue"] in by_issue for a in x["articles"]]
    p3_other = [x for x in p3 if x["issue"] not in by_issue]
    p3_hit, p3_n, p3_miss = coverage(p3_pairs)
    core_pairs = [(i, a) for i, arts in core.items() if i in by_issue for a in arts]
    core_hit, core_n, core_miss = coverage(core_pairs)

    # 2) train 일반 문의 gold_articles 포함률(조 단위). 일반 문의에는 정답 쟁점 표시가 없어서
    #    "gold 조 중 하나라도 어떤 꾸러미의 핵심 조와 같으면 그 꾸러미에 배정"으로 센다.
    gen = [x for x in train if x["intake_type"] == "GENERAL" and x["gold_articles"]]

    def gold_jo(g):
        law, art = g.rsplit(" ", 1)
        j = jo_key(art).split("-")
        return (GOLD_LAW[law], int(j[0]), int(j[1]) if len(j) > 1 else 0)

    all_jo = set().union(*pkg_jo.values())
    gold_total = sum(len(x["gold_articles"]) for x in gen)
    gold_in_any = sum(gold_jo(g) in all_jo for x in gen for g in x["gold_articles"])
    per_pkg = {pid: dict(cases=0, gold=0, covered=0, missed=collections.Counter()) for pid in pk}
    unassigned, unassigned_jo = 0, collections.Counter()
    jo_name = {}
    for x in gen:
        gj = [gold_jo(g) for g in x["gold_articles"]]
        for g, j in zip(x["gold_articles"], gj):
            jo_name[j] = g
        hit = [pid for pid in pk if set(gj) & pkg_core_jo[pid]]
        if not hit:
            unassigned += 1
            unassigned_jo.update(jo_name[j] for j in gj)
        for pid in hit:
            s = per_pkg[pid]
            s["cases"] += 1
            s["gold"] += len(gj)
            for j in gj:
                if j in pkg_jo[pid]:
                    s["covered"] += 1
                else:
                    s["missed"][jo_name[j]] += 1

    # 3) train 구체 문의 expected.issues 분포(그 꾸러미를 읽게 될 문의 수)
    spec_issue = collections.Counter(i["issue_name"] for x in train if x["intake_type"] == "SPECIFIC"
                                     for i in (x["expected"].get("issues") or []))

    # 4) 조건별 글자 수(그 조건에 걸리는 항목 + 조건 없는 항목, 겹침 뺌)
    def cond_chars(pid, cid, parents):
        seen, n = set(), 0
        want = {cid, *parents}
        for it in items[pid]:
            cs = json.loads(it["conds"]) if it["conds"] else None
            if cs is None or want & set(cs):
                for u in units[it["item_id"]]:
                    k = ("a", u["annex_id"]) if u["annex_id"] else ("c", u["chunk_id"])
                    if k not in seen:
                        seen.add(k)
                        n += u["an"] if u["annex_id"] else u["n"]
        return n

    spec_by_id = {x["package_id"]: x for x in spec["packages"]}
    measure = dict(version=spec["version"], packages=len(pk), items=sum(len(v) for v in items.values()), per_package={},
                   p3=dict(hit=p3_hit, total=p3_n, missed=p3_miss, other_issue_lines=[x["id"] for x in p3_other]),
                   core=dict(hit=core_hit, total=core_n, missed=core_miss),
                   train_general=dict(cases=len(gen), gold_total=gold_total, gold_in_any_package=gold_in_any,
                                      unassigned_cases=unassigned, unassigned_gold=dict(unassigned_jo.most_common())),
                   train_specific_issue_counts=dict(spec_issue))
    interp_total = 0
    for pid, r in pk.items():
        s = per_pkg[pid]
        conds = json.loads(r["conditions_json"])
        interp_total += sum(len(c.get("interp", [])) for c in conds)
        cc = {}
        for c in conds:
            parents = c.get("parent", "").split("/") if c.get("parent") else []
            cc[c["id"]] = cond_chars(pid, c["id"], parents)
        roles = collections.Counter(it["role"] for it in items[pid])
        measure["per_package"][pid] = dict(
            issue=r["issue_name"], items=r["item_count"], units=r["unit_count"], law_chars=r["law_chars"],
            annex_chars=r["annex_chars"], guide_chars=r["guide_chars"], total_chars=r["total_chars"],
            basis_chars=r["basis_chars"], not_basis_items=sum(1 for it in items[pid] if not it["answer_basis"]),
            roles=dict(roles), cond_chars=cc, train_general=dict(cases=s["cases"], gold=s["gold"], covered=s["covered"],
                                                                  missed=dict(s["missed"])),
            train_specific_cases=spec_issue.get(r["issue_name"], 0))
    measure["interp_candidates"] = interp_total
    OUT_JSON.write_text(json.dumps(measure, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")

    # ---------------- 검토표 ----------------
    L = []
    w = L.append
    w(f"# 쟁점 꾸러미 {ver} 검토표")
    w("")
    w(f"- 입력 파일: `{files[ver]['file']}`(이 표의 모든 값이 여기서 옴). 고친 뒤 `python3 db/build_db.py && python3 db/make_package_review.py --version {ver}`. "
      f"키가 DB에 없거나 역할 규칙을 어기면 빌드가 멈춘다.")
    w(f"- 꾸러미 {len(pk)}개, 항목 {measure['items']}개, 해석 쟁점 후보 {interp_total}개. 상태: {spec['status']}.")
    w("- 역할: 핵심(쟁점을 만드는 규정) / 요건(지킬 조건) / 예외(본 규정 없이 처리 가능) / 절차(위임된 방법·공개·서식) / 해석(정의·해설·사례). "
      "**즉답** 칸 ○ = ANSWER_BASIS 문서의 사용 범위 안(답변 인용 가능), × = 즉답 근거 아님(인용 검증에서 걸러짐, 역할은 해석만).")
    w("- 출처: 현업(rule_set legal_refs·issue_catalog) / 팀P3(issue_articles.json) / 위임연결(kb_links) / 본문참조 / 안내서목차 / D6계획 / 우리판단(근거 없음, 아래 '사용자가 볼 항목'에 모음).")
    w("- 조건 칸이 비면 그 꾸러미의 모든 조건에 걸린다. 에이전트는 확정 슬롯에 맞는 조건의 항목만 읽을 수 있다(조건별 글자 수 참고).")
    w("")

    # 사용자가 볼 항목
    interp_state = collections.defaultdict(list)
    for x in spec["packages"]:
        for c in x["conditions"]:
            for q in c.get("interp", []):
                if q.get("status"):
                    interp_state[q["status"]].append(q["id"])
    interp_state = dict(interp_state)
    review = []
    for x in spec["packages"]:
        if x["trigger_status"] == "사실 조건":
            review.append((x["package_id"], "쟁점 세우는 조건", f"{x['issue']}: 사실 조건(대응표 결정 8 P-C) `{json.dumps(x['trigger'], ensure_ascii=False)}`. 열어 주는 조건 {', '.join(x.get('opened_by', []))}", x.get("trigger_note", "")))
        if x["trigger_status"] == "제안":
            review.append((x["package_id"], "쟁점 세우는 조건", f"{x['issue']}: 트리거가 현업 슬롯 값에 없는 제안값 `{json.dumps(x['trigger'], ensure_ascii=False)}`. 4단계 쟁점×사실 대응표에서 정할지", "현업 purpose_scope 허용 값은 '수집 목적 내'/'목적 외' 두 개뿐"))
    for pid in pk:
        for it in items[pid]:
            if it["source"] == "우리판단":
                review.append((pid, "넣음(우리판단)", f"{label(it)} — {it['role']}", it["reason"]))
            elif not it["answer_basis"]:
                review.append((pid, "넣음(즉답 근거 아님)", f"{label(it)} — 해석", f"{it['basis_note']}. {it['reason']}"))
    CURATED = [
        ("PKG13", "범위 규칙", "별표는 use_scope에 없음 → '별표 연결로 이어진 조문이 범위 안이면 범위 안'으로 봄. 정보통신망법 시행령 별표 6이 이 규칙으로 즉답 근거(요건)가 됨", "사용 정책 결정 파일에 별표 규칙이 없어서 이 단계에서 정함. 다르게 하려면 결정 파일에 별표 키를 적는다"),
        ("PKG01", "크기", f"수집·이용 근거 {pk['PKG01']['total_chars']:,}자(안내서 {pk['PKG01']['guide_chars']:,}자). 조건별로 읽으면 " + ", ".join(f"{k} {v:,}" for k, v in measure['per_package']['PKG01']['cond_chars'].items()), "가장 큰 절: 법률 특별 규정 8,401자·정당한 이익과 다른 근거 관계 5,785자·동의 원칙 5,659자. 줄일지, 그대로 둘지"),
        ("PKG01", "넣음(팀P3)", "개인정보 보호법 제21조 제1항·제3항(파기) — 예외, 조건 C4(법령)", "팀 P04가 '법령' 근거에 묶은 조문. 현업 규칙에는 없음. 법령상 보존 의무로 보관하는 사안에만 맞음"),
        ("PKG01", "넣음(안내서목차)", "개인정보 보호법 제16조(수집 제한) — 요건", "현업·팀 근거 없음. 통합 안내서 Ⅱ장이 제15조와 함께 다루고 train 일반 정답에 1건"),
        ("PKG01·03", "나눔", "제15조 제3항·제17조 제4항(추가적 이용·제공)을 수집·이용 근거·제3자 제공에서 빼고 PKG14에만 둠", "현업 R-B01 legal_refs는 '제15조' 전체를 가리킴. 두 꾸러미가 동시에 열리면 같이 읽힘"),
        ("PKG02", "뺌", "개인정보 보호법 제19조(제공받은 자의 목적 외 이용 제한)", "현업·팀 근거 없음. 받는 쪽 문의가 있으면 넣는다"),
        ("PKG05", "세부 조건", "국외 이전을 하위 조건 C1a(제3자 제공과 동시, 현업 R-M01)·C1b(위탁·보관, 팀 P12)로 나눔. 중지명령·상호주의(제28조의9~11)는 뺌", "트리거는 cross_border=있음 하나라 이전 유형은 다른 슬롯에서 읽는다"),
        ("PKG09", "넣음/뺌", "제28조의2·제28조의4·제28조의5·시행령 제29조의5를 넣고, 시행령 제29조의2·제29조의4(전문기관 지정·감독)는 뺌", "결합 뒤 처리자 의무는 넣고 전문기관 쪽 규정은 뺌. 위임연결은 둘 다 있음"),
        ("PKG10", "빈 곳", "자동화된 결정 안내서가 인용하는 '자동화된 결정 고시'(고시 제3·6·7·8조)가 DB에 없음", "RAG 등록목록 v1.2(1·2단계·보류·제외 100행)에 이 고시가 없다. 안내서 절로만 들어감"),
        ("PKG11·12·13", "빈 곳", "위치정보 2개·광고성 정보 꾸러미에 ANSWER_BASIS 안내서 절이 없음(위치정보 해설서·불법 스팸 안내서가 등록목록 v1.2에서 빠짐). REFERENCE 절로 메움", "즉답은 조문만으로 하게 됨. 사용 정책을 바꿀지는 2단계 결정 사항"),
        ("전체", "해석 쟁점", f"해석 쟁점 후보 {interp_total}개(꾸러미별 표 아래). 각각 '요건 의미가 갈린다'로 확정할지", "확정한 것만 에이전트가 결론 대신 검토 쪽으로 보냄(설계계획 D6)")
        if not interp_state else
        ("전체", "해석 쟁점", f"해석 쟁점 후보 {interp_total}개 상태: " + ", ".join(f"{k} {len(v)}" for k, v in interp_state.items())
         + f". 해석 쟁점(②) = {', '.join(interp_state.get('해석 쟁점', []))}", "대응표 결정 10(3절 표). ①은 사실 ID로 풀고 ③은 기록만 남김"),
    ]
    if p3_miss:
        CURATED.append(("전체", "팀 P3 빠짐", "; ".join(f"{m['issue']} {m['cite']}" for m in p3_miss), "아래 3.2"))
    review.extend(CURATED)
    fz = spec.get("frozen", {}).get(spec["version"])

    def decision(pid, kind):
        if not fz:
            return "검토 전"
        if kind in ("쟁점 세우는 조건", "해석 쟁점"):
            if ver != "v1":
                return "결정 8 P-C로 바꿈" if kind == "쟁점 세우는 조건" else "결정 10 반영(①20/②3/③9)"
            return "보류: 4단계 쟁점×사실 대응표에서 결정(지금 값 유지)"
        if pid == "PKG10" and kind == "빈 곳":
            return "넣지 않음: 등록목록 v1.2 밖이라 받지 않음"
        return "확정(지금 값)"

    w(f"## 1. 사용자가 볼 항목 ({len(review)}개)")
    w("")
    if fz:
        w(f"**{spec['version']} 고정**: {fz['decided_by']}, {fz['decided_at']}. {fz['note']}. "
          f"이후 꾸러미 내용을 바꾸면 빌드가 멈추므로 version을 v2로 올린다(`변경_규칙`).")
        w("")
    w("판단이 갈리는 것만 모았다. 나머지 항목은 현업·팀P3·위임연결·안내서 목차 근거가 있다.")
    w("")
    w("| # | 꾸러미 | 종류 | 무엇 | 왜 / 고려할 점 | 결정 |")
    w("|---|---|---|---|---|---|")
    for i, (pid, kind, what, why) in enumerate(review, 1):
        w(f"| {i} | {pid} | {kind} | {what} | {why} | {decision(pid, kind)} |")
    w("")

    # 숫자 요약
    w("## 2. 크기와 포함률")
    w("")
    w("| 꾸러미 | 쟁점 | 항목 | 원문 단위 | 법령 | 별표 | 안내서 | 합계(자) | 즉답 근거 글자 | 즉답 아님 항목 | train 구체 문의 수 | train 일반: 배정 문의 / gold 조 포함 |")
    w("|---|---|---|---|---|---|---|---|---|---|---|---|")
    for pid, m in measure["per_package"].items():
        t = m["train_general"]
        cov = f"{t['cases']} / {t['covered']}/{t['gold']}" + (f" ({t['covered'] / t['gold']:.0%})" if t["gold"] else "")
        w(f"| {pid} | {m['issue']} | {m['items']} | {m['units']} | {m['law_chars']:,} | {m['annex_chars']:,} | {m['guide_chars']:,} | "
          f"{m['total_chars']:,} | {m['basis_chars']:,} | {m['not_basis_items']} | {m['train_specific_cases']} | {cov} |")
    tot = sum(m["total_chars"] for m in measure["per_package"].values())
    w("")
    w(f"- 글자 수 = 현행 원문(법령 청크 text, 별표 읽기용 글, 안내서 절 아래 청크 text) 합, 꾸러미 안 겹침은 한 번만. 14개 합 {tot:,}자.")
    w(f"- 팀 P3 조문(13개 쟁점의 {len(set(x['id'] for x in p3 if x['issue'] in by_issue))}줄 {p3_n}개 조항) 포함: **{p3_hit}/{p3_n}**. "
      f"꾸러미가 없는 팀 줄 {len(p3_other)}개({', '.join(x['id'] + ' ' + x['issue'] for x in p3_other)})는 세지 않음.")
    w(f"- 팀 핵심 조문(issue_core_articles, 13개 쟁점 {core_n}개) 포함: **{core_hit}/{core_n}**.")
    w(f"- train 일반 문의 {len(gen)}건의 gold 조 {gold_total}개 중 어느 꾸러미에든 든 것: **{gold_in_any}/{gold_total} ({gold_in_any / gold_total:.1%})**. "
      f"일반 문의에는 정답 쟁점 표시가 없어서, gold 조가 그 꾸러미 '핵심' 조와 하나라도 같으면 그 꾸러미에 배정하고 그 문의 gold 조 전부를 꾸러미 조(역할 무관)와 비교했다. "
      f"배정되지 않은 문의 {unassigned}건(쟁점 꾸러미 밖 주제)의 gold 조: " + ", ".join(f"{k} {v}" for k, v in unassigned_jo.most_common(12)) + " 등.")
    w("- train 구체 문의는 gold_articles가 0건이라 조문 포함률을 셀 수 없다. 대신 `expected.issues`에 그 쟁점이 있는 문의 수(= 그 꾸러미를 읽게 될 문의 수)를 적었다.")
    w("")
    w("### 2.1 train 일반 문의에서 꾸러미에 없던 gold 조")
    w("")
    w("| 꾸러미 | 빠진 gold 조(건수) |")
    w("|---|---|")
    for pid, m in measure["per_package"].items():
        if m["train_general"]["missed"]:
            w(f"| {pid} {m['issue']} | " + ", ".join(f"{k} {v}" for k, v in m["train_general"]["missed"].items()) + " |")
    w("")
    w("### 2.2 팀 P3·핵심 조문 중 빠진 것")
    w("")
    if not p3_miss and not core_miss:
        w("- 없음.")
    for m in p3_miss + core_miss:
        w(f"- {m['issue']}: {m['cite']} (DB에 있음: {'예' if m['in_db'] else '아니오'})")
    w("")
    w("### 2.3 팀 쟁점에만 있는 4가지의 현업 처리 방식(꾸러미 만들지 않음)")
    w("")
    for k, v in TEAM_ONLY:
        w(f"- **{k}**: {v}")
    w("")

    # 꾸러미별
    w("## 3. 꾸러미별")
    w("")
    for x in spec["packages"]:
        pid = x["package_id"]
        r, m = pk[pid], measure["per_package"][pid]
        w(f"### {pid} {x['issue']}")
        w("")
        w(f"- 출처: {x['origin']}. 트리거({x['trigger_status']}): `{json.dumps(x['trigger'], ensure_ascii=False)}`"
          + (f" — {x['trigger_note']}" if x.get("trigger_note") else ""))
        if x["rules"]:
            w("- 현업 규칙: " + "; ".join(f"{rid}({rules[rid]['grade']}) {rules[rid]['name']} → {', '.join(rules[rid]['legal_refs'])}" for rid in x["rules"]))
        w(f"- 크기: {r['total_chars']:,}자(법령 {r['law_chars']:,} / 별표 {r['annex_chars']:,} / 안내서 {r['guide_chars']:,}), 항목 {r['item_count']}개. "
          "조건별: " + ", ".join(f"{k} {v:,}자" for k, v in m["cond_chars"].items()))
        w("")
        w("세부 조건과 해석 쟁점 후보:")
        w("")
        w("| 조건 | 종류 | 언제 | 팀 P3 | 해석 쟁점 후보(요건 의미가 갈리는가) | 근거 조문·문구 |")
        w("|---|---|---|---|---|---|")
        for c in x["conditions"]:
            when = json.dumps(c["when"], ensure_ascii=False)
            if c.get("facts"):
                when += " + 사실 " + json.dumps(c["facts"], ensure_ascii=False)
            kind = c["kind"] + (f"(상위 {c['parent']}, {c.get('source', '')})" if c["kind"] == "하위" else "")
            if c["kind"] == "사실 조건":
                extra = [f"상위 {c['parent']}" if c.get("parent") else "", f"열기 {c['opens']}" if c.get("opens") else "",
                         f"표시 끔 {','.join(c['interp_off'])}" if c.get("interp_off") else "", c.get("source", "")]
                kind += "(" + ", ".join(e for e in extra if e) + ")"
            qs = c.get("interp") or []
            if not qs:
                w(f"| {c['id']} | {kind} | `{when}` | {c.get('team_p3') or ''} | (없음) | |")
            for i, q in enumerate(qs):
                lead = f"| {c['id']} | {kind} | `{when}` | {c.get('team_p3') or ''} |" if i == 0 else "| | | | |"
                k = q["basis"]
                art = con.execute("SELECT article, doc_id FROM kb_chunks WHERE status='CURRENT' AND (unit_key=? OR substr(unit_key,1,?)=?) ORDER BY chunk_id",
                                  (k, len(k), k)).fetchone()
                tag = f"**{q['id']} {q['status']}**" + (f" 사실 {', '.join(q['facts'])}" if q.get("facts") else "") + (
                    f" (→ {q['merged_into']})" if q.get("merged_into") else "") + ": " if q.get("status") else ""
                w(f"{lead} {tag}{q['q']} | {docs[art['doc_id']]['law_name']} {art['article'] if k.count('|') > 2 else art['article'].split(' ')[0]}: “{q['phrase']}” |")
        w("")
        w("| # | 종류 | 대상 | 역할 | 즉답 | 출처 | 조건 | 글자 | 고른 이유 |")
        w("|---|---|---|---|---|---|---|---|---|")
        for it in items[pid]:
            cs = ",".join(json.loads(it["conds"])) if it["conds"] else ""
            kind = {"LAW_UNIT": "조문", "ANNEX": "별표", "GUIDE_SECTION": "안내서"}[it["target_kind"]]
            w(f"| {it['ord']} | {kind} | {label(it)} `{it['target_key']}` | {it['role']} | {'○' if it['answer_basis'] else '×'} | "
              f"{it['source']} | {cs} | {it['char_count']:,} | {it['reason']} |")
        if x.get("excluded"):
            w("")
            w("일부러 뺀 것: " + " / ".join(f"`{e['key']}` — {e['why']}" for e in x["excluded"]))
        w("")
    OUT_MD.write_text("\n".join(L) + "\n", encoding="utf-8")
    print(json.dumps(dict(packages=len(pk), items=measure["items"], review=len(review), interp=interp_total,
                          p3=f"{p3_hit}/{p3_n}", core=f"{core_hit}/{core_n}", gold_any=f"{gold_in_any}/{gold_total}",
                          unassigned=unassigned), ensure_ascii=False))


if __name__ == "__main__":
    main()
