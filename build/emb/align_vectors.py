"""안내서 청크 벡터 맞추기 → MySQL 적재 파일 15_kb_embeddings.sql + manifest 갱신.

입력(읽기 전용)
- data/out/db/privacyask.db: 우리 kb_chunks(안내서 청크: chunk_key·text·text_hash)
- ref/.../emb/s1_guide·s2_guide: 동료 문서 벡터(KURE-v3, 1024차원, float32, 정규화, `passage: ` 접두)
- ref/.../kb/stage1·2 안내서 청크 jsonl: 동료가 임베딩한 글(embed_text)과 text_hash

맞추는 법: chunk_key가 같고 text_hash(그 벡터를 만든 청크 글)도 같으면 동료 벡터를 바이트 그대로 쓴다.
키가 없거나 글이 바뀐 청크만 같은 모델·같은 접두·float32로 새로 만든다(--allow-new, data/.venv-emb 필요).
새로 만들 때 넣는 글은 `[문서 제목 > 절 경로]\n본문`이다. 동료 embed_text의 '(계속)' 표시·상위 절 이름은
우리 DB에서 다시 만들 수 없어 글이 조금 다를 수 있다(보고서 new_embed_text_note).

출력
- data/out/mysql/15_kb_embeddings.sql: 10_kb.sql 다음에 넣는다(10이 kb_chunks를 embedding NULL로 다시 넣으므로).
  SET NAMES → START TRANSACTION → UPDATE kb_chunks SET embedding = X'…' WHERE chunk_key = '…' → COMMIT → 확인 SELECT.
  같은 입력이면 바이트까지 같은 파일.
- data/out/mysql/manifest.json에 files["15_kb_embeddings.sql"]·embeddings 칸을 더한다(다른 칸은 그대로).
  적재/export_mysql.py를 다시 돌리면 manifest가 새로 써지므로 이 스크립트도 다시 돌린다.
- data/out/emb/align_report.json: 맞은 수·새로 만든 수·바뀐 글.

실행(build/에서): python3 emb/align_vectors.py   (새로 만들 청크가 있으면 ../data/.venv-emb/bin/python … --allow-new)
"""
import argparse
import hashlib
import json
import sqlite3
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import emb_common as C  # noqa: E402

P = C.P
STMT_HEAD = "UPDATE `kb_chunks` SET `embedding` = X'"


def read_bundle_vectors():
    """chunk_key → float32 LE 4,096바이트(동료 npy 그대로). numpy 없이 npy를 읽는다(빌드 기본 python3로 돌게)."""
    out = {}
    for part in C.EMB_PARTS:
        meta = json.loads((C.EMB_DIR / part / "embedding_meta.json").read_text(encoding="utf-8"))
        if meta["model"] != C.MODEL or meta["dim"] != C.DIM or meta["document_prefix"] != C.PASSAGE_PREFIX:
            raise SystemExit("%s: 모델·차원·접두가 다르다 %s" % (part, {k: meta.get(k) for k in ("model", "dim", "document_prefix")}))
        rows = read_npy_rows(C.EMB_DIR / part / "embeddings.npy")
        keys = meta["chunk_keys"]
        if len(rows) != len(keys):
            raise SystemExit("%s: 행 %d != 키 %d" % (part, len(rows), len(keys)))
        for k, b in zip(keys, rows):
            if k in out:
                raise SystemExit("같은 chunk_key가 두 번: %s" % k)
            out[k] = (part, b)
    return out


def read_npy_rows(path: Path):
    """npy v1/v2/v3, '<f4', C 순서, [n, 1024]만 받는다."""
    import ast
    raw = path.read_bytes()
    if raw[:6] != b"\x93NUMPY":
        raise SystemExit("npy 아님: %s" % path)
    major = raw[6]
    if major == 1:
        hlen, off = int.from_bytes(raw[8:10], "little"), 10
    else:
        hlen, off = int.from_bytes(raw[8:12], "little"), 12
    header = ast.literal_eval(raw[off:off + hlen].decode("latin1"))
    if header["descr"] != "<f4" or header["fortran_order"] or len(header["shape"]) != 2 or header["shape"][1] != C.DIM:
        raise SystemExit("npy 꼴이 다르다: %s %s" % (path, header))
    body = raw[off + hlen:]
    n = header["shape"][0]
    if len(body) != n * C.VECTOR_BYTES:
        raise SystemExit("npy 길이가 다르다: %s" % path)
    return [body[i * C.VECTOR_BYTES:(i + 1) * C.VECTOR_BYTES] for i in range(n)]


def read_bundle_chunks():
    out = {}
    for f in C.BUNDLE_GUIDE_CHUNKS:
        for line in open(f, encoding="utf-8"):
            if line.strip():
                c = json.loads(line)
                out[c["chunk_key"]] = {"text_hash": c.get("text_hash"), "text": c.get("text")}
    return out


def read_our_chunks(db: Path):
    con = sqlite3.connect("file:%s?mode=ro" % db, uri=True)
    try:
        return con.execute(
            "SELECT c.chunk_key, c.text, c.text_hash, d.title, s.path_text FROM kb_chunks c "
            "JOIN kb_documents d USING(doc_id) LEFT JOIN guide_sections s USING(section_id) "
            "WHERE d.source_type = 'GUIDE' AND c.chunk_key IS NOT NULL ORDER BY c.chunk_id").fetchall()
    finally:
        con.close()


def new_embed_text(doc_title, path_text, text):
    head = path_text or doc_title
    return "[%s]\n%s" % (head, text)


def align(ours, bundle_vec, bundle_chunks):
    """→ (rows [(chunk_key, blob 또는 None, 출처)], report)."""
    matched, changed, missing = [], [], []
    rows = []
    for key, text, text_hash, title, path_text in ours:
        bv = bundle_vec.get(key)
        bc = bundle_chunks.get(key)
        if bv is not None and bc is not None and bc["text_hash"] == text_hash and bc["text"] == text:
            rows.append((key, bv[1], "BUNDLE:" + bv[0]))
            matched.append(key)
        else:
            rows.append((key, None, "NEW"))
            (changed if bv is not None else missing).append(key)
    report = {"ours": len(ours), "matched": len(matched), "new": len(changed) + len(missing),
              "new_text_changed": changed, "new_no_vector": missing,
              "bundle_only": sorted(set(bundle_vec) - {r[0] for r in ours})}
    return rows, report


def embed_new(rows, ours):
    need = [i for i, r in enumerate(rows) if r[1] is None]
    if not need:
        return rows
    sys.path.insert(0, str(HERE))
    from export_onnx import load_st, st_encode
    st = load_st(C.snapshot_dir())
    by_key = {o[0]: o for o in ours}
    texts = [new_embed_text(by_key[rows[i][0]][3], by_key[rows[i][0]][4], by_key[rows[i][0]][1]) for i in need]
    E = st_encode(st, texts, "document")
    rows = list(rows)
    for i, v in zip(need, E):
        rows[i] = (rows[i][0], C.to_blob(v), "NEW")
    return rows


def build_sql(rows) -> bytes:
    lines = ["-- 안내서 청크 벡터(%s rev %s, %d차원, float32 LE, 정규화, 접두 %r). 10_kb.sql 다음에 넣는다."
             % (C.MODEL, C.REVISION, C.DIM, C.PASSAGE_PREFIX),
             "-- 만든 것: build/emb/align_vectors.py. 같은 입력이면 같은 파일.",
             "SET NAMES utf8mb4;", "START TRANSACTION;"]
    for key, blob, _src in rows:
        if blob is None or len(blob) != C.VECTOR_BYTES:
            raise SystemExit("벡터 없음: %s" % key)
        if not all(ch in "0123456789abcdef" for ch in key):
            raise SystemExit("chunk_key 꼴이 다르다: %s" % key)
        lines.append("%s%s' WHERE `chunk_key` = '%s';" % (STMT_HEAD, blob.hex(), key))
    lines.append("COMMIT;")
    lines.append("SELECT 'kb_chunks.embedding 안내서' AS tbl, COUNT(*) AS actual, %d AS expected, "
                 "IF(COUNT(*) = %d, 'OK', 'DIFF') AS ok FROM `kb_chunks` c JOIN `kb_documents` d USING(doc_id) "
                 "WHERE d.source_type = 'GUIDE' AND c.embedding IS NOT NULL AND LENGTH(c.embedding) = %d;"
                 % (len(rows), len(rows), C.VECTOR_BYTES))
    return ("\n".join(lines) + "\n").encode("utf-8")


def update_manifest(man_path: Path, sql: bytes, report):
    man = json.loads(man_path.read_text(encoding="utf-8")) if man_path.exists() else {"files": {}}
    man.setdefault("files", {})[C.EMB_SQL] = {
        "bytes": len(sql), "sha256": hashlib.sha256(sql).hexdigest(), "source": "privacyask.db + 확정 묶음 emb",
        "tables": ["kb_chunks.embedding"], "after": "10_kb.sql"}
    man["embeddings"] = {"model": C.MODEL, "revision": C.REVISION, "dim": C.DIM, "dtype": "float32-le",
                         "normalized": True, "passage_prefix": C.PASSAGE_PREFIX, "rows": report["ours"],
                         "matched_bundle": report["matched"], "new": report["new"]}
    man_path.write_text(json.dumps(man, ensure_ascii=False, indent=1, sort_keys=True) + "\n", encoding="utf-8")


def run(db=P.KB_DB, out=P.MYSQL_OUT, report_path=C.EMB_REPORT, allow_new=False):
    ours = read_our_chunks(db)
    rows, report = align(ours, read_bundle_vectors(), read_bundle_chunks())
    if report["new"] and not allow_new:
        raise SystemExit("새로 만들 청크 %d개: --allow-new로 다시(../data/.venv-emb/bin/python)" % report["new"])
    rows = embed_new(rows, ours)
    sql = build_sql(rows)
    out.mkdir(parents=True, exist_ok=True)
    (out / C.EMB_SQL).write_bytes(sql)
    update_manifest(out / "manifest.json", sql, report)
    if report_path is not None:   # None = 보고를 쓰지 않음(다른 출력 폴더로 export할 때)
        report_path.parent.mkdir(parents=True, exist_ok=True)
        report_path.write_text(json.dumps(report, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    return report, sql


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--allow-new", action="store_true")
    a = ap.parse_args()
    report, sql = run(allow_new=a.allow_new)
    print("안내서 청크 %d: 동료 벡터 맞음 %d, 새로 만듦 %d (글 바뀜 %d, 벡터 없음 %d), 묶음에만 %d"
          % (report["ours"], report["matched"], report["new"], len(report["new_text_changed"]),
             len(report["new_no_vector"]), len(report["bundle_only"])))
    print("%s %d바이트" % (C.EMB_SQL, len(sql)))


if __name__ == "__main__":
    main()
