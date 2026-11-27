"""build/emb 테스트. 실행: python3 -m unittest emb.test_emb -v  (build/에서, numpy 없어도 됨)

- npy 읽기(꼴이 다르면 멈춤), 맞추기(키·글이 같으면 동료 벡터, 글이 바뀌거나 벡터 없으면 NEW), SQL 꼴·결정성
- 데이터가 있으면: 15_kb_embeddings.sql의 행 수 = 안내서 청크 수, SQL 벡터 = 동료 npy 바이트, 길이 1, manifest sha256
- 모델 파일이 있으면: model_manifest.json·query_check.json의 질의 코사인
"""
import hashlib
import json
import math
import re
import struct
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import align_vectors as A  # noqa: E402
import emb_common as C  # noqa: E402


def blob(seed: float) -> bytes:
    v = [math.sin(seed + i) for i in range(C.DIM)]
    n = math.sqrt(sum(x * x for x in v))
    return struct.pack("<%df" % C.DIM, *[x / n for x in v])


def write_npy(path: Path, rows, descr="<f4", dim=C.DIM):
    header = "{'descr': '%s', 'fortran_order': False, 'shape': (%d, %d), }" % (descr, len(rows), dim)
    pad = 64 - (10 + len(header) + 1) % 64
    h = (header + " " * pad + "\n").encode("latin1")
    path.write_bytes(b"\x93NUMPY\x01\x00" + struct.pack("<H", len(h)) + h + b"".join(rows))


def floats(b: bytes):
    return struct.unpack("<%df" % C.DIM, b)


class UnitTests(unittest.TestCase):
    def test_npy_read(self):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / "e.npy"
            rows = [blob(1), blob(2), blob(3)]
            write_npy(p, rows)
            self.assertEqual(A.read_npy_rows(p), rows)
            write_npy(p, rows, descr="<f8")
            with self.assertRaises(SystemExit):
                A.read_npy_rows(p)

    def test_align_kinds(self):
        ours = [("aa", "글1", "h1", "문서", "문서 > 절"), ("bb", "글2-바뀜", "h2x", "문서", None), ("cc", "글3", "h3", "문서", "문서 > 절3")]
        vec = {"aa": ("s1_guide", blob(1)), "bb": ("s2_guide", blob(2)), "dd": ("s2_guide", blob(4))}
        chunks = {"aa": {"text_hash": "h1", "text": "글1"}, "bb": {"text_hash": "h2", "text": "글2"}}
        rows, rep = A.align(ours, vec, chunks)
        self.assertEqual([r[2] for r in rows], ["BUNDLE:s1_guide", "NEW", "NEW"])
        self.assertEqual(rows[0][1], blob(1))
        self.assertEqual((rep["matched"], rep["new"]), (1, 2))
        self.assertEqual(rep["new_text_changed"], ["bb"])
        self.assertEqual(rep["new_no_vector"], ["cc"])
        self.assertEqual(rep["bundle_only"], ["dd"])

    def test_new_embed_text(self):
        self.assertEqual(A.new_embed_text("문서", "문서 > 절", "본문"), "[문서 > 절]\n본문")
        self.assertEqual(A.new_embed_text("문서", None, "본문"), "[문서]\n본문")

    def test_sql_shape(self):
        rows = [("ab12", blob(1), "BUNDLE:s1_guide"), ("cd34", blob(2), "NEW")]
        sql = A.build_sql(rows)
        self.assertEqual(sql, A.build_sql(rows))
        text = sql.decode("utf-8")
        ups = [l for l in text.splitlines() if l.startswith(A.STMT_HEAD)]
        self.assertEqual(len(ups), 2)
        m = re.match(r"UPDATE `kb_chunks` SET `embedding` = X'([0-9a-f]+)' WHERE `chunk_key` = '([0-9a-f]+)';$", ups[0])
        self.assertEqual(bytes.fromhex(m.group(1)), blob(1))
        self.assertEqual(m.group(2), "ab12")
        self.assertIn("START TRANSACTION;", text)
        self.assertTrue(text.rstrip().endswith("'DIFF') AS ok FROM `kb_chunks` c JOIN `kb_documents` d USING(doc_id) "
                                               "WHERE d.source_type = 'GUIDE' AND c.embedding IS NOT NULL AND LENGTH(c.embedding) = 4096;"))
        with self.assertRaises(SystemExit):
            A.build_sql([("ab12", None, "NEW")])
        with self.assertRaises(SystemExit):
            A.build_sql([("x'; DROP", blob(1), "NEW")])


@unittest.skipUnless((C.P.MYSQL_OUT / C.EMB_SQL).exists() and C.P.KB_DB.exists() and C.EMB_DIR.exists(), "적재 파일·DB·묶음 없음")
class DataTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.sql = (C.P.MYSQL_OUT / C.EMB_SQL).read_bytes()
        cls.rows = {}
        for line in cls.sql.decode("utf-8").splitlines():
            m = re.match(r"UPDATE `kb_chunks` SET `embedding` = X'([0-9a-f]+)' WHERE `chunk_key` = '([0-9a-f]+)';$", line)
            if m:
                cls.rows[m.group(2)] = bytes.fromhex(m.group(1))

    def test_every_guide_chunk_once(self):
        ours = A.read_our_chunks(C.P.KB_DB)
        self.assertEqual(sorted(self.rows), sorted(o[0] for o in ours))
        self.assertTrue(all(len(b) == C.VECTOR_BYTES for b in self.rows.values()))

    def test_bundle_bytes_and_norm(self):
        vec = A.read_bundle_vectors()
        rep = json.loads(C.EMB_REPORT.read_text(encoding="utf-8"))
        new = set(rep["new_text_changed"]) | set(rep["new_no_vector"])
        for k, b in self.rows.items():
            if k not in new:
                self.assertEqual(b, vec[k][1], k)
        for b in list(self.rows.values())[::97]:
            n = math.sqrt(sum(x * x for x in floats(b)))
            self.assertAlmostEqual(n, 1.0, places=4)

    def test_manifest(self):
        man = json.loads((C.P.MYSQL_OUT / "manifest.json").read_text(encoding="utf-8"))
        f = man["files"][C.EMB_SQL]
        self.assertEqual(f["sha256"], hashlib.sha256(self.sql).hexdigest())
        self.assertEqual(f["bytes"], len(self.sql))
        self.assertEqual(man["embeddings"]["rows"], len(self.rows))
        self.assertEqual(man["embeddings"]["matched_bundle"] + man["embeddings"]["new"], len(self.rows))


@unittest.skipUnless(C.QUERY_CHECK.exists(), "질의 모델 확인 파일 없음(export_onnx.py 전)")
class ModelTests(unittest.TestCase):
    def test_query_cosine(self):
        q = json.loads(C.QUERY_CHECK.read_text(encoding="utf-8"))
        self.assertEqual(len(q["queries"]), len(C.CHECK_QUERIES))
        self.assertGreaterEqual(q["cos_query_st_vs_onnx_int8"]["min"], 0.98)     # 동료 기준: 8비트는 0.98 이상
        self.assertGreaterEqual(q["cos_doc_bundle_vs_st_float32"]["min"], 0.999)  # 같은 모델로 다시 만든 문서 벡터

    def test_model_files(self):
        man = json.loads((C.MODELS_OUT / "model_manifest.json").read_text(encoding="utf-8"))
        names = {f["name"] for f in man["files"]}
        self.assertTrue({"model_int8pc.onnx", "model_int8pc.onnx.data", "tokenizer.json"} <= names)


if __name__ == "__main__":
    unittest.main()
