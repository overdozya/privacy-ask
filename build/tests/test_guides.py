"""2단계 안내서·절 목록 테스트. 실행: python3 -m unittest discover -s tests -v  (build/에서)

- 안내서 25건·청크 3,925개가 묶음과 글자·chunk_key·text_hash까지 같다(완료 기준 1)
- 안내서 청크가 모두 guide_sections 잎 하나에 속한다(누락 0, 중복 0), 절 ID는 경로에서 다시 만들어진다(완료 기준 2)
- 재생성 동일은 test_db.RebuildTests가 guide_sections까지 포함해 본다
"""
import json
import sqlite3
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent  # build/
sys.path.insert(0, str(ROOT))
import paths as P  # noqa: E402
sys.path.insert(0, str(ROOT / "db"))
import guides as gd  # noqa: E402

DB = P.KB_DB
BUNDLE = P.BUNDLE_KB
GUIDE_DOCS = set(range(7, 16)) | set(range(35, 51))


class GuideTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if not DB.exists():
            raise unittest.SkipTest("data/out/db/privacyask.db 없음. 먼저 python3 db/build_db.py")
        cls.c = sqlite3.connect(DB)
        cls.c.row_factory = sqlite3.Row

    @classmethod
    def tearDownClass(cls):
        cls.c.close()

    def test_guide_documents(self):
        rows = self.c.execute("SELECT * FROM kb_documents WHERE source_type='GUIDE'").fetchall()
        self.assertEqual({r["doc_id"] for r in rows}, GUIDE_DOCS)
        bundle = {}
        for f in ("stage1/guide_documents.jsonl", "stage2/guide2_documents.jsonl"):
            for line in (BUNDLE / f).read_text(encoding="utf-8").splitlines():
                d = json.loads(line)
                bundle[d["doc_id"]] = (d["title"], d["stage"])
        self.assertEqual({r["doc_id"]: (r["title"], r["stage"]) for r in rows}, bundle)
        self.assertTrue(all(r["law_name"] == r["title"] for r in rows))

    def test_guide_chunks_equal_bundle(self):
        db = {r["chunk_key"]: r for r in self.c.execute(
            "SELECT c.*, d.title AS doc_title FROM kb_chunks c JOIN kb_documents d USING(doc_id) WHERE d.source_type='GUIDE'")}
        n = {"stage1/guide_chunks_current.jsonl": 0, "stage2/guide2_chunks_current.jsonl": 0}
        for f in n:
            for line in (BUNDLE / f).read_text(encoding="utf-8").splitlines():
                b = json.loads(line)
                n[f] += 1
                r = db.pop(b["chunk_key"])
                self.assertEqual((r["text"], r["text_hash"], r["article"], r["doc_id"], r["doc_title"],
                                  [r["page_from"], r["page_to"]]),
                                 (b["text"], b["text_hash"], b["article"], b["doc_id"], b["guide"], b["pages"]))
                self.assertEqual(gd.sha(r["text"]), r["text_hash"])
        self.assertEqual(list(n.values()), [1253, 2672])
        self.assertEqual(db, {})  # DB에만 있는 안내서 청크 없음

    def test_every_guide_chunk_in_one_leaf(self):
        c = self.c
        self.assertEqual(c.execute("SELECT count(*) FROM kb_chunks c JOIN kb_documents d USING(doc_id) "
                                   "WHERE d.source_type='GUIDE' AND c.section_id IS NULL").fetchone()[0], 0)
        self.assertEqual(c.execute("SELECT count(*) FROM kb_chunks c JOIN kb_documents d USING(doc_id) "
                                   "WHERE d.source_type='LAW' AND c.section_id IS NOT NULL").fetchone()[0], 0)
        self.assertEqual(c.execute("SELECT count(*) FROM kb_chunks c JOIN guide_sections s USING(section_id) "
                                   "WHERE s.is_leaf=0").fetchone()[0], 0)
        # 잎마다 청크가 있고, 잎의 자기 청크 수 합 = 안내서 청크 수 = 뿌리 노드 청크 수 합
        self.assertEqual(c.execute("SELECT count(*) FROM guide_sections WHERE is_leaf=1 AND own_chunk_count=0").fetchone()[0], 0)
        own = c.execute("SELECT sum(own_chunk_count) FROM guide_sections").fetchone()[0]
        roots = c.execute("SELECT sum(chunk_count) FROM guide_sections WHERE parent_id IS NULL").fetchone()[0]
        self.assertEqual((own, roots), (3925, 3925))
        self.assertEqual(c.execute("SELECT count(*) FROM guide_sections WHERE parent_id IS NULL").fetchone()[0], 25)

    def test_section_ids_from_path(self):
        ids = set()
        for r in self.c.execute("SELECT section_id, doc_id, path, parent_id FROM guide_sections"):
            path = tuple(json.loads(r["path"]))
            self.assertEqual(r["section_id"], gd.section_id(r["doc_id"], path))
            ids.add(r["section_id"])
            if r["parent_id"]:
                self.assertEqual(r["parent_id"], gd.section_id(r["doc_id"], path[:-1]))
        self.assertEqual(len(ids), self.c.execute("SELECT count(*) FROM guide_sections").fetchone()[0])

    def test_read_section_covers_subtree(self):
        """절 읽기 = 노드 아래 잎 청크. 아래 청크 수가 chunk_count와 같아야 한다."""
        tree = {}
        for r in self.c.execute("SELECT section_id, parent_id FROM guide_sections"):
            tree.setdefault(r["parent_id"], []).append(r["section_id"])
        own = {r[0]: r[1] for r in self.c.execute("SELECT section_id, own_chunk_count FROM guide_sections")}

        def count(s):
            return own[s] + sum(count(ch) for ch in tree.get(s, []))
        for r in self.c.execute("SELECT section_id, chunk_count FROM guide_sections"):
            self.assertEqual(count(r["section_id"]), r["chunk_count"])

    def test_guide_policy_set(self):
        # 다-2 뒤: 안내서 25건 모두 정책이 있다(범위 검사는 test_policy.py)
        self.assertEqual(self.c.execute("SELECT count(*) FROM kb_documents WHERE source_type='GUIDE' "
                                        "AND use_policy IS NULL").fetchone()[0], 0)

if __name__ == "__main__":
    unittest.main()
