"""실험 03 원장 행 ID(첨부1·2·5·7, 828행) → 처리 원장 행 ID 대응표.

왜: 설계 문서와 실험 기록이 실험 03의 행 ID(예 '첨부5-0119')를 인용한다. 새 추출기는 쪽 경계·여러 열 칸을
다르게(더 맞게) 다뤄 번호가 밀린다. 옛 ID로 새 행을 찾을 수 있게 대응표를 남긴다.
방법: 칸 글(공백 제거)을 이어 붙인 문자열로 두 목록을 순서 맞춤(difflib)하고, 같지 않은 구간은
같은 서비스·받는 자 안에서 글 유사도가 가장 높은 새 행에 붙인다(유사도 함께 기록).
실행(build/에서): python3 ledger/map_exp03.py → data/out/ledger/점검/실험03_행ID_대응.json
"""
import difflib
import json
import re
import sqlite3
from pathlib import Path

import sys
HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))  # build/: paths.py
import paths as P  # noqa: E402
OLD = P.EXP03_LEDGER
ns = lambda s: re.sub(r"\s+", "", s)  # noqa: E731


def main():
    con = sqlite3.connect(P.LEDGER_DB)
    out, summary = {}, {}
    for n in (1, 2, 5, 7):
        old = json.loads((OLD / f"a{n}.json").read_text(encoding="utf-8"))["rows"]
        new = con.execute("SELECT row_id, fields_json FROM ledger_rows WHERE attachment=? ORDER BY seq",
                          (f"첨부{n}",)).fetchall()
        ok = ["|".join(ns(v) for v in r["fields"].values()) for r in old]
        nk = ["|".join(ns(v) for _, v in json.loads(f)) for _, f in new]
        sm = difflib.SequenceMatcher(None, ok, nk, autojunk=False)
        same = 0
        for tag, i1, i2, j1, j2 in sm.get_opcodes():
            for i in range(i1, i2):
                if tag == "equal":
                    out[old[i]["row_id"]] = {"new": new[j1 + i - i1][0], "같음": True, "유사도": 1.0}
                    same += 1
                    continue
                cands = range(max(0, j1 - 3), min(len(new), max(j2, j1 + 1) + 3))
                best = max(cands, key=lambda j: difflib.SequenceMatcher(None, ok[i], nk[j], autojunk=False).ratio(),
                           default=None)
                r = difflib.SequenceMatcher(None, ok[i], nk[best], autojunk=False).ratio() if best is not None else 0
                out[old[i]["row_id"]] = {"new": new[best][0] if best is not None else None, "같음": False,
                                         "유사도": round(r, 3)}
        summary[f"첨부{n}"] = {"실험03 행": len(old), "새 행": len(new), "글까지 같은 행": same}
    P.LEDGER_CHECK.mkdir(parents=True, exist_ok=True)
    (P.LEDGER_CHECK / "실험03_행ID_대응.json").write_text(
        json.dumps({"요약": summary, "대응": out}, ensure_ascii=False, indent=1), encoding="utf-8")
    print(json.dumps(summary, ensure_ascii=False))
    for k in ("첨부5-0119", "첨부5-0130"):
        print(k, "→", out[k])


if __name__ == "__main__":
    main()
