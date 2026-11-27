"""2단계 다-2 사용 정책 테스트. 실행: python3 -m unittest discover -s tests -v  (build/에서)

- 50건 모두 use_policy가 있고 정한 사람·날짜·메모가 있다(완료 기준 6)
- DB 값이 입력 파일 data/수집/사용정책_결정.json과 같다
- ANSWER_BASIS의 use_scope 접두는 모두 DB 현행 조문 키에, 절 ID는 같은 문서의 guide_sections에 닿는다
- REFERENCE·OUT은 use_scope가 없다. 재생성 동일은 test_db.RebuildTests가 본다
"""
import json
import sqlite3
import unittest
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent  # build/
sys.path.insert(0, str(ROOT))
import paths as P  # noqa: E402
DB = P.KB_DB
DECISION = P.POLICY_FILE


class PolicyTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if not DB.exists():
            raise unittest.SkipTest("data/out/db/privacyask.db 없음. 먼저 python3 db/build_db.py")
        cls.c = sqlite3.connect(DB)
        cls.c.row_factory = sqlite3.Row
        cls.docs = {r["doc_id"]: r for r in cls.c.execute("SELECT * FROM kb_documents")}

    @classmethod
    def tearDownClass(cls):
        cls.c.close()

    def test_no_null_policy(self):
        self.assertEqual(len(self.docs), 50)
        for d, r in self.docs.items():
            self.assertIn(r["use_policy"], ("ANSWER_BASIS", "REFERENCE", "OUT"), d)
            self.assertEqual((r["use_policy_by"], r["use_policy_at"]), ("사용자", "2026-11-26"), d)
            self.assertTrue(r["use_policy_note"], d)

    def test_db_equals_decision_file(self):
        dec = json.loads(DECISION.read_text(encoding="utf-8"))["documents"]
        self.assertEqual(sorted(int(k) for k in dec), sorted(self.docs))
        for k, v in dec.items():
            r = self.docs[int(k)]
            self.assertEqual(r["use_policy"], v["use_policy"], k)
            self.assertEqual(json.loads(r["use_scope"]) if r["use_scope"] else None, v["use_scope"], k)
            self.assertEqual(r["use_policy_note"], v["note"], k)

    def test_answer_basis_scope_reaches_db(self):
        n_prefix = n_section = 0
        for d, r in self.docs.items():
            if r["use_policy"] != "ANSWER_BASIS":
                self.assertIsNone(r["use_scope"], d)
                continue
            scope = json.loads(r["use_scope"])
            self.assertTrue(scope, d)
            for k in scope:
                if r["source_type"] == "GUIDE":
                    row = self.c.execute("SELECT doc_id FROM guide_sections WHERE section_id=?", (k,)).fetchone()
                    self.assertIsNotNone(row, (d, k))
                    self.assertEqual(row[0], d)
                    n_section += 1
                else:
                    hit = self.c.execute("SELECT count(*) FROM kb_chunks WHERE doc_id=? AND status='CURRENT' "
                                         "AND substr(unit_key, 1, length(?)) = ?", (d, k, k)).fetchone()[0]
                    self.assertGreater(hit, 0, (d, k))
                    n_prefix += 1
        self.assertEqual((n_prefix, n_section), (234, 13))


if __name__ == "__main__":
    unittest.main()
