"""1단계 법령 DB 테스트(법령 행만 본다. 안내서는 test_guides.py). 실행: python3 -m unittest discover -s tests -v  (build/에서)

- 키 생성과 인용 표기 복원(완료 기준 2·6)
- 재생성 시 같은 결과(완료 기준 6)
- 연결이 실제 조문·별표에 닿는지, 못 닿으면 이유가 있는지(완료 기준 4)
- 별표 언급이 모두 별표 테이블에 닿는지(완료 기준 3)
DB가 없으면 먼저 python3 db/build_db.py 를 실행한다.
"""
import hashlib
import json
import re
import sqlite3
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent  # build/
sys.path.insert(0, str(ROOT))
import paths as P  # noqa: E402
sys.path.insert(0, str(ROOT / "db"))
import build_db as bd  # noqa: E402
import lawparse as lp  # noqa: E402

DB = P.KB_DB
BUNDLE = P.BUNDLE_KB


def con():
    c = sqlite3.connect(DB)
    c.row_factory = sqlite3.Row
    return c


class KeyTests(unittest.TestCase):
    def test_make_key(self):
        self.assertEqual(bd.make_key("개인정보보호법", 28, 8, 1), "개인정보보호법|28-8|1||")
        self.assertEqual(bd.make_key("개인정보보호법", 2, 0, None, "1-2"), "개인정보보호법|2||1-2|")
        self.assertEqual(bd.make_key("정보통신망법", 76, 0, 4, "2-2~4-6"), "정보통신망법|76|4|2-2~4-6|")
        self.assertEqual(bd.make_key("개인정보보호법", 1, 0), "개인정보보호법|1|||")

    def test_cite_from_parts(self):
        self.assertEqual(bd.cite_from_parts(15, 0, 1), "제15조 제1항")
        self.assertEqual(bd.cite_from_parts(28, 8, 1), "제28조의8 제1항")
        self.assertEqual(bd.cite_from_parts(2, 0, None, "1-2", "1-2"), "제2조 제1호의2")
        self.assertEqual(bd.cite_from_parts(64, 2, 1, "8", "9"), "제64조의2 제1항 제8호~제9호")
        self.assertEqual(bd.cite_from_parts(62, 0, 3, "10-2", "12"), "제62조 제3항 제10호의2~제12호")

    def test_parse_key_roundtrip(self):
        for key in ["개인정보보호법|28-8|1||", "정보통신망법|76|4|2-2~4-6|", "상법|1|||", "개인정보보호법|2||1-2|"]:
            law, jo, sub, hang, hf, ht, mok = bd.parse_key(key)
            ho = None if hf is None else (hf if hf == ht else f"{hf}~{ht}")
            self.assertEqual(bd.make_key(law, jo, sub, hang, ho, mok), key)

    def test_parser_helpers(self):
        self.assertEqual(lp.hang_no("①"), 1)
        self.assertEqual(lp.hang_no("<16>"), 16)
        self.assertEqual(lp.deleted_date("③ 삭제 <2020.2.4>"), "2020.2.4")
        self.assertEqual(lp.deleted_date("⑥ 삭제"), "?")
        self.assertIsNone(lp.deleted_date("① 개인정보처리자는 삭제하여야 한다."))
        # 행정규칙: 호 번호가 앞 글에 붙어 있어도("사항2.") 다음 번호만 찾는다
        lead, hos = lp.split_items("다음 각 호의 사항을 포함한다.1. 조직에 관한 사항2. 제2조에 관한 사항10. 끝")
        self.assertEqual([h.label for h in hos], ["1", "2"])
        self.assertIn("제2조", hos[1].text)


class DbTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if not DB.exists():
            raise unittest.SkipTest("data/out/db/privacyask.db 없음. 먼저 python3 db/build_db.py")
        cls.c = con()

    @classmethod
    def tearDownClass(cls):
        cls.c.close()

    def test_25_documents_from_api(self):
        rows = self.c.execute("SELECT * FROM kb_documents WHERE source_type='LAW'").fetchall()
        self.assertEqual(len(rows), 25)
        self.assertTrue(all(r["sync_source"] == "LAW_API" and r["content_hash"] for r in rows))
        # 약칭은 확정 묶음 law_name과 글자까지 같다
        bundle = {}
        for f in ("stage1/law_documents.jsonl", "stage2/law2_documents.jsonl"):
            for line in (BUNDLE / f).read_text(encoding="utf-8").splitlines():
                d = json.loads(line)
                bundle[d["doc_id"]] = d["law_name"]
        self.assertEqual({r["doc_id"]: r["law_name"] for r in rows}, bundle)

    def test_use_policy(self):
        # 1단계 reference_only 5건(5장 범위 밖)은 다-2 결정에서도 REFERENCE다(전체 확인은 test_policy.py)
        ref = {r[0] for r in self.c.execute("SELECT doc_id FROM kb_documents WHERE use_policy='REFERENCE'")}
        self.assertTrue({22, 23, 29, 30, 31} <= ref)

    def test_every_chunk_has_unique_key(self):
        n, k = self.c.execute("SELECT count(*), count(DISTINCT unit_key) FROM kb_chunks WHERE status='CURRENT'").fetchone()
        self.assertEqual(n, k)
        self.assertEqual(self.c.execute("SELECT count(*) FROM kb_chunks WHERE unit_key IS NULL OR unit_key=''").fetchone()[0], 0)

    def test_citation_restored_from_key(self):
        bad = []
        for r in self.c.execute("SELECT c.unit_key, c.article, d.law_name FROM kb_chunks c JOIN kb_documents d USING(doc_id) "
                                "WHERE d.source_type='LAW'"):
            _, jo, sub, hang, hf, ht, _ = bd.parse_key(r["unit_key"])
            if bd.cite_from_parts(jo, sub, hang, hf, ht) != r["article"]:
                bad.append(r["unit_key"])
        self.assertEqual(bad, [])

    def test_bundle_stage1_citations_exist(self):
        """1단계 확정 묶음 인용 표기(law_name + article) 중 DB에 그대로 있는 비율. 호 묶음 경계 차이만 예외."""
        have = {(r[0], r[1]) for r in self.c.execute("SELECT law_name, article FROM kb_chunks JOIN kb_documents USING(doc_id) WHERE status='CURRENT' AND source_type='LAW'")}
        units = {(l, re.match(r"(제\d+조(?:의\d+)? ?(?:제\d+항)?)", a).group(1).strip()) for l, a in have}
        miss_exact, miss_unit = 0, 0
        rows = [json.loads(l) for l in (BUNDLE / "stage1/law_chunks_current.jsonl").read_text(encoding="utf-8").splitlines()]
        for b in rows:
            if (b["law_name"], b["article"]) not in have:
                miss_exact += 1
                u = re.match(r"(제\d+조(?:의\d+)? ?(?:제\d+항)?)", b["article"]).group(1).strip()
                if (b["law_name"], u) not in units:
                    miss_unit += 1
        self.assertEqual(miss_unit, 0)          # 조·항 단위로는 전부 있다
        self.assertLessEqual(miss_exact / len(rows), 0.02)  # 청크 표기 그대로는 98% 이상

    def test_links_reach_real_targets(self):
        c = self.c
        # 해결된 연결은 실제 청크·별표·문서를 가리킨다
        self.assertEqual(c.execute("""SELECT count(*) FROM kb_links l WHERE resolution IN ('정확','상위단위')
            AND NOT EXISTS (SELECT 1 FROM kb_chunks x WHERE x.chunk_id=l.dst_chunk_id AND x.status='CURRENT')
            AND NOT EXISTS (SELECT 1 FROM kb_annexes a WHERE a.annex_id=l.dst_annex_id)""").fetchone()[0], 0)
        self.assertEqual(c.execute("SELECT count(*) FROM kb_links WHERE resolution='문서' AND dst_doc_id IS NULL").fetchone()[0], 0)
        # 못 닿은 연결은 모두 이유가 있다
        self.assertEqual(c.execute("SELECT count(*) FROM kb_links WHERE resolution='미해결' AND unresolved_reason IS NULL").fetchone()[0], 0)
        # 출발점도 실제 청크·별표다
        self.assertEqual(c.execute("""SELECT count(*) FROM kb_links l WHERE
            NOT EXISTS (SELECT 1 FROM kb_chunks x WHERE x.chunk_id=l.src_chunk_id)
            AND NOT EXISTS (SELECT 1 FROM kb_annexes a WHERE a.annex_id=l.src_annex_id)""").fetchone()[0], 0)

    def test_annex_mentions_resolved(self):
        rows = self.c.execute("""SELECT resolution, unresolved_reason FROM kb_links
                                 WHERE link_type='별표' AND origin='본문'""").fetchall()
        self.assertTrue(rows)
        self.assertTrue(all(r[0] == "정확" or r[1] == "KB밖_법령" for r in rows))
        # 본문에 "별표/별지"가 나오는 현행 청크는 모두 별표 연결을 가진다("식별표시"·"특별지원"은 낱말 일부라 뺀다)
        missing = []
        for cid, text in self.c.execute("SELECT chunk_id, text FROM kb_chunks JOIN kb_documents USING(doc_id) "
                                         "WHERE status='CURRENT' AND source_type='LAW'"):
            if re.search(r"(?<![식특])별(?:표|지)", text) and not self.c.execute(
                    "SELECT 1 FROM kb_links WHERE src_chunk_id=? AND link_type='별표'", (cid,)).fetchone():
                missing.append(cid)
        self.assertEqual(missing, [])

    def test_pending_rows(self):
        r = self.c.execute("SELECT count(*), min(effective_from) FROM kb_chunks WHERE status='PENDING'").fetchone()
        self.assertGreater(r[0], 0)
        self.assertGreater(r[1], "2026-11-25")


class AsOfDateTests(unittest.TestCase):
    """같은 조문 단위는 현행·예정본이 같은 unit_key를 갖고, effective_from/effective_to로
    '기준일 D에 시행 중인 판'을 한 행으로 고를 수 있어야 한다(사안 기준일 판단용)."""

    @classmethod
    def setUpClass(cls):
        if not DB.exists():
            raise unittest.SkipTest("data/out/db/privacyask.db 없음")
        cls.c = con()

    @classmethod
    def tearDownClass(cls):
        cls.c.close()

    def keys(self, day, prefix):
        return [(r["unit_key"], r["status"]) for r in bd.in_force(self.c, day, prefix)]

    def test_changed_unit_same_key(self):
        k = "개인정보보호법|7-9|1||"   # 2027-03-09 시행 예정 개정
        self.assertEqual(self.keys("2026-12-01", k), [(k, "CURRENT")])
        self.assertEqual(self.keys("2027-03-09", k), [(k, "PENDING")])
        self.assertEqual(self.keys("2027-03-08", k), [(k, "CURRENT")])

    def test_new_unit_only_after_start(self):
        k = "개인정보보호법|2||9|"     # 2027-03-09에 새로 생기는 호
        self.assertEqual(self.keys("2026-12-01", k), [])
        self.assertEqual(self.keys("2027-03-09", k), [(k, "PENDING")])

    def test_regrouped_paragraph_has_no_stale_rows(self):
        p = "개인정보보호법|75|2|"     # 2027-07-01부터 호 묶음 경계가 바뀜
        before = [k for k, _ in self.keys("2026-12-01", p)]
        after = [k for k, _ in self.keys("2027-07-01", p)]
        self.assertIn("개인정보보호법|75|2|10~14-4|", before)
        self.assertNotIn("개인정보보호법|75|2|10~14-4|", after)
        self.assertIn("개인정보보호법|75|2|10~15|", after)

    def test_at_most_one_row_per_key_on_any_date(self):
        days = sorted({r[0] for r in self.c.execute(
            "SELECT DISTINCT effective_from FROM kb_chunks JOIN kb_documents USING(doc_id) "
            "WHERE status IN ('CURRENT','PENDING') AND source_type='LAW'")})
        days += ["2026-11-25", "2030-01-01"]
        for day in days:
            rows = [r["unit_key"] for r in bd.in_force(self.c, day, "")]
            self.assertEqual(len(rows), len(set(rows)), day)


class RebuildTests(unittest.TestCase):
    def test_rebuild_is_deterministic(self):
        with tempfile.TemporaryDirectory() as t:
            a, b = Path(t) / "a.db", Path(t) / "b.db"
            bd.build(P.SOURCE_DEFAULT, a)
            bd.build(P.SOURCE_DEFAULT, b)
            self.assertEqual(bd.content_digest(a), bd.content_digest(b))
            self.assertEqual(hashlib.sha256(a.read_bytes()).hexdigest(), hashlib.sha256(b.read_bytes()).hexdigest())
            if DB.exists():
                self.assertEqual(bd.content_digest(a), bd.content_digest(DB))


if __name__ == "__main__":
    unittest.main()
