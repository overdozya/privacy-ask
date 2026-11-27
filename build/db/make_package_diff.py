#!/usr/bin/env python3
"""꾸러미 두 버전의 차이표 → data/out/db/꾸러미_<새 버전>_변경.md. DB(build_db.py 결과)와 입력 파일만 읽는다.
실행: python3 db/make_package_diff.py [--old v1] [--new v2]
"""
from __future__ import annotations

import argparse
import collections
import json
import sqlite3
from pathlib import Path

import sys
HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))  # build/: paths.py
import paths as P  # noqa: E402
DB = P.KB_DB
ITEM_COLS = ("ord", "role", "source", "reason", "conds", "answer_basis", "basis_note", "char_count")

# 왜 DB에 두 버전을 다 두나(지금 판 표시는 is_current). 보고서·README와 같은 문장
WHY_BOTH = ("DB에는 v1·v2를 둘 다 넣고 `issue_packages.is_current`로 지금 판(v2)을 표시한다. 이유: "
            "(1) 평가는 꾸러미 버전을 기록하고 같은 버전끼리 비교해야 한다(AGENTS.md 설계 원칙). v1으로 돌린 결과를 다시 볼 때 "
            "옛 커밋으로 DB를 다시 만들지 않아도 된다. (2) 이 차이표처럼 두 버전 비교를 SQL 한 번으로 한다. "
            "(3) 비용이 작다: v2 행은 항목 286개·원문 단위 행만 더 늘고, 원문(청크)은 두 버전이 같이 가리킨다")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--old", default="v1")
    ap.add_argument("--new", default="v2")
    a = ap.parse_args()
    con = sqlite3.connect(DB)
    con.row_factory = sqlite3.Row
    files = json.loads(con.execute("SELECT v FROM build_info WHERE k='package_files'").fetchone()[0])
    spec = {v: json.loads((P.DATA / files[v]["file"]).read_text(encoding="utf-8")) for v in (a.old, a.new)}
    pk = {v: {r["package_id"]: r for r in con.execute("SELECT * FROM issue_packages WHERE version=? ORDER BY package_id", (v,))}
          for v in (a.old, a.new)}
    items = {v: {(r["package_id"], r["target_kind"], r["target_key"]): r for r in con.execute(
        "SELECT * FROM package_items WHERE version=?", (v,))} for v in (a.old, a.new)}
    o, n = a.old, a.new
    added = sorted(set(items[n]) - set(items[o]))
    removed = sorted(set(items[o]) - set(items[n]))
    changed = []
    for k in sorted(set(items[o]) & set(items[n])):
        d = [c for c in ITEM_COLS if items[o][k][c] != items[n][k][c]]
        if d:
            changed.append((k, d))

    def conds(v, pid):
        return {c["id"]: c for c in json.loads(pk[v][pid]["conditions_json"])}

    def interp(v):
        return [(x["package_id"], c["id"], q) for x in spec[v]["packages"] for c in x["conditions"] for q in c.get("interp", [])]

    cond_added, cond_changed = [], []
    for pid in pk[n]:
        co, cn = conds(o, pid), conds(n, pid)
        for cid in cn:
            if cid not in co:
                cond_added.append((pid, cid, cn[cid]))
            else:
                strip = lambda c: {k: v for k, v in c.items() if k != "interp"}  # noqa: E731
                if strip(co[cid]) != strip(cn[cid]):
                    keys = sorted(k for k in set(strip(co[cid])) | set(strip(cn[cid])) if co[cid].get(k) != cn[cid].get(k))
                    cond_changed.append((pid, cid, keys))
    state = collections.Counter(q.get("status") for _, _, q in interp(n))

    L = []
    w = L.append
    w(f"# 쟁점 꾸러미 {o} → {n} 변경")
    w("")
    w(f"- 만든 것: `{files[n]['file']}`({n}, 상태 {files[n]['status']}). `{files[o]['file']}`({o}, {files[o]['status']})은 그대로 둔다.")
    w(f"- 근거: {spec[n].get('근거', '')}")
    w(f"- 다시 만들기: `python3 db/build_db.py && python3 db/make_package_diff.py`")
    w("")
    w("## 1. 한눈에")
    w("")
    n_cond = {v: sum(len(json.loads(r["conditions_json"])) for r in pk[v].values()) for v in (o, n)}
    trig = [pid for pid in pk[n] if pk[o][pid]["trigger_json"] != pk[n][pid]["trigger_json"]]
    w(f"| 무엇 | {o} | {n} | 차이 |")
    w("|---|---|---|---|")
    w(f"| 꾸러미 | {len(pk[o])} | {len(pk[n])} | 0 |")
    w(f"| 항목 | {len(items[o])} | {len(items[n])} | 더함 {len(added)}, 뺌 {len(removed)}, **바뀜 {len(changed)}** |")
    w(f"| 세부 조건 | {n_cond[o]} | {n_cond[n]} | 더함 {len(cond_added)}, 바뀜 {len(cond_changed)} |")
    w(f"| trigger가 바뀐 꾸러미 | | | {len(trig)} ({', '.join(trig)}) |")
    w(f"| 해석 쟁점 후보 | {len(interp(o))}(상태 없음) | {len(interp(n))} | " + ", ".join(f"{k} {v}" for k, v in state.items()) + " |")
    w("")
    w("## 2. 꾸러미별")
    w("")
    w(f"| 꾸러미 | 쟁점 | 조건 {o}→{n} | 항목 | 바뀐 항목 | trigger | 해석 후보 ①/②/③ |")
    w("|---|---|---|---|---|---|---|")
    for pid, r in pk[n].items():
        c = collections.Counter(q.get("status") for p, _, q in interp(n) if p == pid)
        nchg = sum(1 for k, _ in changed if k[0] == pid)
        w(f"| {pid} | {r['issue_name']} | {len(conds(o, pid))}→{len(conds(n, pid))} | {r['item_count']} | {nchg} | "
          f"{pk[o][pid]['trigger_status']}→{r['trigger_status'] if pid in trig else '같음'} | "
          f"{c.get('사실로 풂', 0)}/{c.get('해석 쟁점', 0)}/{c.get('뺌', 0)} |")
    w("")
    w("## 3. 바뀐 것")
    w("")
    w("### 3.1 trigger")
    for pid in trig:
        w(f"- **{pid}** {pk[n][pid]['issue_name']}: `{pk[o][pid]['trigger_json']}`({pk[o][pid]['trigger_status']}) → "
          f"`{pk[n][pid]['trigger_json']}`({pk[n][pid]['trigger_status']}). {pk[n][pid]['trigger_note']}")
    w("")
    w("### 3.2 세부 조건")
    w("")
    w("| 꾸러미 | 조건 | 무엇 |")
    w("|---|---|---|")
    for pid, cid, c in cond_added:
        facts = " + ".join(f"{f['fact']} {f['op']} {f['value']}" for f in c.get("facts", []))
        extra = "; ".join(e for e in (f"열기 {c['opens']}" if c.get("opens") else "",
                                      f"해석 표시 끔 {','.join(c['interp_off'])}" if c.get("interp_off") else "",
                                      c.get("note", "")) if e)
        w(f"| {pid} | {cid}(더함, {c['kind']}) | `{json.dumps(c['when'], ensure_ascii=False)}` + {facts}. {c.get('source', '')}"
          + (f". {extra}" if extra else "") + " |")
    for pid, cid, keys in cond_changed:
        w(f"| {pid} | {cid}(바뀜) | 바뀐 칸: {', '.join(keys)} |")
    w("")
    w("### 3.3 항목")
    w("")
    if not (added or removed or changed):
        w("- 없음.")
    w("| 꾸러미 | 대상 | 바뀐 칸 | " + o + " | " + n + " |")
    w("|---|---|---|---|---|")
    for k, d in changed:
        w(f"| {k[0]} | `{k[2]}` | {', '.join(d)} | " + "; ".join(str(items[o][k][c]) for c in d) + " | "
          + "; ".join(str(items[n][k][c]) for c in d) + " |")
    for k in added:
        w(f"| {k[0]} | `{k[2]}` | 더함 | | |")
    for k in removed:
        w(f"| {k[0]} | `{k[2]}` | 뺌 | | |")
    w("")
    w("### 3.4 해석 쟁점 후보(대응표 결정 10)")
    w("")
    w("| I | 꾸러미·조건 | 상태 | 사실 ID | 질문 |")
    w("|---|---|---|---|---|")
    for pid, cid, q in interp(n):
        more = "".join(f" {k} {json.dumps(q[k], ensure_ascii=False)}" for k in ("on_when", "off_when", "merged_into") if k in q)
        w(f"| {q.get('id', '')} | {pid} {cid} | {q.get('status', '')} | {', '.join(q.get('facts', []))}{more} | {q['q']} |")
    w("")
    w("## 4. DB에 두는 방식")
    w("")
    w(f"- {WHY_BOTH}.")
    w("- 키: `issue_packages`는 (version, package_id), `package_items`는 version 칸이 있고 item_id는 버전을 넘어 이어 붙인다. "
      "`build_info.package_current`가 지금 판, `package_files`가 버전별 입력 파일·해시·상태.")
    w("- 고정: 버전마다 입력 파일 `frozen`에 내용 지문을 둔다. 고정된 버전 내용을 바꾸면 빌드가 멈춘다(다음 번호로 올려야 함).")
    w("")
    (P.DB_OUT / f"꾸러미_{n}_변경.md").write_text("\n".join(L) + "\n", encoding="utf-8")
    print(json.dumps(dict(items_changed=len(changed), items_added=len(added), items_removed=len(removed),
                          conds=f"{n_cond[o]}->{n_cond[n]}", cond_added=len(cond_added), cond_changed=len(cond_changed),
                          trigger=trig, interp=dict(state)), ensure_ascii=False))


if __name__ == "__main__":
    main()
