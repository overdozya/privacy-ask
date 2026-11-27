"""사안 DB 하나만 읽어 A0~A8 각 단계의 입력·출력과 기록을 표로 다시 보인다(완료 기준 3).
privacyask.db·ledger.db는 열지 않는다: 이 표가 나오면 "이 테이블만으로 다시 보임"이다.

실행: python3 case/replay.py   (build/에서. data/out/case/case.db → data/out/case/예시사안_재현표.md)
"""
import argparse
import json
import sqlite3
from pathlib import Path

import sys
HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))  # build/: paths.py
import paths as P  # noqa: E402
STEPS = ["A0", "A1", "A2", "A3", "A4", "A5", "A6", "A7", "A8"]
STEP_NAMES = {"A0": "입구 판정", "A1": "사실 추출", "A2": "다음 질문", "A3": "기준선 대조·쟁점", "A4": "꾸러미 열기",
              "A5": "답변", "A6": "보조 검색", "A7": "검증·출구", "A8": "기록"}


def cell(x) -> str:
    s = x if isinstance(x, str) else json.dumps(x, ensure_ascii=False, separators=(",", ":"))
    return s.replace("|", "\\|").replace("\n", " ")


def fact_value(con, fr):
    vs = con.execute("SELECT label, detail FROM fact_values WHERE fact_row_id=? ORDER BY ord", (fr,)).fetchall()
    return ", ".join(l + (f"[{d}]" if d else "") for l, d in vs) or "(값 없음)"


def replay(db: Path) -> str:
    con = sqlite3.connect(db)
    con.row_factory = sqlite3.Row
    out = []
    w = out.append
    for case in con.execute("SELECT * FROM cases ORDER BY case_id"):
        cid = case["case_id"]
        w(f"# 사안 재현표: {cid}\n")
        w("`python3 case/replay.py`가 사안 DB(`case/case.db`)만 읽어 만든다. 손으로 고치지 않는다.\n")
        w("## 사안 (cases)\n")
        w("| 칸 | 값 |\n|---|---|")
        for k in ("requester_id", "intake_type", "out_of_scope_kind", "state", "rule_set_version", "package_version", "ledger_version", "service_name", "reference_date", "reference_date_basis", "created_at", "updated_at"):
            w(f"| {k} | {cell(case[k]) if case[k] is not None else '—'} |")
        w(f"| content | {cell(case['content'])} |\n")

        flows = con.execute("SELECT * FROM flows WHERE case_id=? ORDER BY flow_no", (cid,)).fetchall()
        w("## 흐름 (flows)\n")
        w("| 흐름 | 이름 | 출발 | 도착 | 서비스 | 만든 단계 |\n|---|---|---|---|---|---|")
        for f in flows:
            w(f"| {f['flow_no']} | {f['label']} | {f['origin']} | {f['destination']} | {f['service_name'] or '—'} | #{f['created_step']} |")
        w("")

        w("## 단계별 입력·출력 (steps + 그 단계가 남긴 행)\n")
        w("| # | 단계 | 주체 | 하는 일 | 판(프롬프트·모델 / 코드) | 입력 | 출력 | 남긴 행 |\n|---|---|---|---|---|---|---|---|")
        steps = con.execute("SELECT * FROM steps WHERE case_id=? ORDER BY seq", (cid,)).fetchall()
        for s in steps:
            ver = (f"{s['prompt_id']} {s['prompt_version']}, {s['model_name']}, t={s['temperature']}"
                   if s["actor"] == "LLM" else (s["code_version"] or "—"))
            made = []
            nf = con.execute("SELECT count(*) FROM facts WHERE case_id=? AND source_step=?", (cid, s["seq"])).fetchone()[0]
            if nf:
                made.append(f"facts {nf}")
            nfl = con.execute("SELECT count(*) FROM flows WHERE case_id=? AND created_step=?", (cid, s["seq"])).fetchone()[0]
            if nfl:
                made.append(f"flows {nfl}")
            tq = [r[0] for r in con.execute("SELECT turn_no FROM turns WHERE case_id=? AND step_seq=? ORDER BY turn_no", (cid, s["seq"]))]
            if tq:
                made.append("turns " + ",".join(map(str, tq)))
            js = [r[0] for r in con.execute("SELECT judgment_id FROM case_judgments WHERE case_id=? AND step_seq=?", (cid, s["seq"]))]
            if js:
                made.append("judgment " + ",".join(map(str, js)))
            st = "" if s["status"] == "OK" else f" ({s['status']}, {s['attempts']}회)"
            w(f"| {s['seq']} | {s['step']} | {s['actor']}{st} | {s['name']} | {cell(ver)} | {cell(json.loads(s['input_json']))} | "
              f"{cell(json.loads(s['output_json']))} | {', '.join(made) or '—'} |")
        w("")

        w("## 단계 덮음 (A0~A8)\n")
        w("| 단계 | 이름 | 기록 수 | 비고 |\n|---|---|---|---|")
        for a in STEPS:
            rows = [s for s in steps if s["step"] == a]
            note = ""
            if not rows and a == "A6":
                gaps = [json.loads(s["output_json"]).get("gap") for s in steps if s["step"] == "A5"]
                note = f"부르지 않음: A5 gap = {cell(gaps)}"
            seqs = ", ".join("#" + str(s["seq"]) for s in rows)
            w(f"| {a} | {STEP_NAMES[a]} | {len(rows)} | {note or seqs} |")
        w("")

        w("## 질문과 답 (turns)\n")
        w("| 턴 | 누가 | 종류 | 흐름 | 사실 | 몇 번째 | 받는 질문 | 글 | 보기·선택 |\n|---|---|---|---|---|---|---|---|---|")
        for t in con.execute("SELECT * FROM turns WHERE case_id=? ORDER BY turn_no", (cid,)):
            w(f"| {t['turn_no']} | {t['role']} | {t['kind']} | {t['flow_no'] or '—'} | {t['fact_id'] or '—'} | {t['ask_no'] or '—'} | "
              f"{t['reply_to'] or '—'} | {cell(t['text'])} | {cell(json.loads(t['choices_json']))} |")
        w("")

        w("## 사실 경과 (facts, 판을 쌓음)\n")
        w("| 행 | 흐름 | 사실 | 판 | 값 | 확인 상태 | 출처 | 근거 문구 | 원장 행 | 적은 단계 |\n|---|---|---|---|---|---|---|---|---|---|")
        for f in con.execute("SELECT * FROM facts WHERE case_id=? ORDER BY fact_row_id", (cid,)):
            src = f["source_kind"] + (f" 턴{f['source_turn']}" if f["source_turn"] else "") + (f" {f['ledger_version']}" if f["ledger_version"] else "")
            lr = ",".join(r[0] for r in con.execute("SELECT row_id FROM fact_ledger_rows WHERE fact_row_id=? ORDER BY ord", (f["fact_row_id"],)))
            w(f"| {f['fact_row_id']} | {f['flow_no'] or '공통'} | {f['fact_id']} | {f['rev']} | {cell(fact_value(con, f['fact_row_id']))} | "
              f"{f['status']} | {src} | {cell(f['basis_quote'] or '—')} | {lr or '—'} | #{f['source_step']} |")
        w("")

        for j in con.execute("SELECT * FROM case_judgments WHERE case_id=? ORDER BY judgment_id", (cid,)):
            jid = j["judgment_id"]
            w(f"## 판정 {jid} (case_judgments)\n")
            w("| 칸 | 값 |\n|---|---|")
            for k in ("step_seq", "judged_turn", "reference_date", "rule_set_version", "package_version", "ledger_version",
                      "fact_map_sha256", "model_name", "temperature", "prompt_versions", "case_grade", "exit_type", "exit_reasons",
                      "conclusive", "flags", "review_bundle", "premise_note", "created_at"):
                w(f"| {k} | {cell(j[k]) if j[k] is not None else '—'} |")
            w("")
            w("### 흐름별 슬롯 (case_judgment_slots + case_judgment_slot_values)\n")
            w("| 흐름 | 슬롯 | 상태 | 값 | 근거 사실(행: 사실 판) | 현업 미확인 |\n|---|---|---|---|---|---|")
            for s in con.execute("""SELECT s.* FROM case_judgment_slots s JOIN slot_catalog c USING (slot_key)
                                    WHERE judgment_id=? ORDER BY flow_no, c.sort_order""", (jid,)):
                vals = [r[0] for r in con.execute("SELECT value FROM case_judgment_slot_values WHERE judgment_id=? AND flow_no=? AND slot_key=? ORDER BY ord",
                                                  (jid, s["flow_no"], s["slot_key"]))]
                basis = []
                for fr in json.loads(s["basis_facts"]):
                    r = con.execute("SELECT fact_id, rev FROM facts WHERE fact_row_id=?", (fr,)).fetchone()
                    basis.append(f"{fr}:{r['fact_id']}@{r['rev']}={fact_value(con, fr)}")
                w(f"| {s['flow_no']} | {s['slot_key']} | {s['status']} | {cell(vals) if vals else '—'} | {cell('; '.join(basis)) or '—'} | "
                  f"{cell(json.loads(s['unknown_facts'])) if json.loads(s['unknown_facts']) else '—'} |")
            w("")
            w("### 쟁점과 인용 (case_judgment_issues + case_judgment_citations)\n")
            w("| 흐름 | 쟁점 | 꾸러미 | 등급 | 규칙 | 조건 | 해석 쟁점 | 결론 확정 | 인용(종류 키 해시 앞 12자) |\n|---|---|---|---|---|---|---|---|---|")
            for i in con.execute("SELECT * FROM case_judgment_issues WHERE judgment_id=? ORDER BY issue_row_id", (jid,)):
                cites = [f"{c['cite_kind']} {c['cite_key']} {c['text_hash'][:12]}" + (f" ({c['ledger_version']})" if c["ledger_version"] else "")
                         for c in con.execute("SELECT * FROM case_judgment_citations WHERE issue_row_id=? ORDER BY ord", (i["issue_row_id"],))]
                w(f"| {i['flow_no']} | {i['issue_name']} | {i['package_id']} | {i['grade']} | {cell(json.loads(i['rule_ids']))} | "
                  f"{cell(json.loads(i['conditions']))} | {cell(json.loads(i['interp_flags']))} | {i['conclusive']} | {cell('; '.join(cites))} |")
            rest = [f"{c['cite_kind']} {c['cite_key']}" for c in con.execute(
                "SELECT * FROM case_judgment_citations WHERE judgment_id=? AND issue_row_id IS NULL ORDER BY ord", (jid,))]
            if rest:
                w(f"\n판정 전체 인용: {'; '.join(rest)}")
            w("")
    con.close()
    return "\n".join(out).rstrip() + "\n"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--db", default=str(P.CASE_DB))
    ap.add_argument("--out", default=str(P.CASE_REPLAY))
    a = ap.parse_args()
    Path(a.out).write_text(replay(Path(a.db)), encoding="utf-8")
    print(a.out)


if __name__ == "__main__":
    main()
