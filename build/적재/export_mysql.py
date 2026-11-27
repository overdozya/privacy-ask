"""SQLite 빌드 결과 → 로컬 MySQL 적재 SQL 4개 + manifest.json (06 3절).

입력(읽기 전용): data/out/의 db/privacyask.db(D1~D7), ledger/ledger.db(D8), case/case.db(D9 기준표 5개)
출력: data/out/mysql/10_kb.sql, 20_packages.sql, 30_ledger.sql, 40_case_catalog.sql, manifest.json
MySQL은 띄우지 않는다. 실행 순서와 주의는 docs/결과/S1_적재_순서.md(로컬 문서).

형식(06 3.3): SET NAMES → START TRANSACTION → 자기 테이블 DELETE(자식 먼저) → INSERT … VALUES (500행·1MB씩) → COMMIT
→ 끝에 행 수 확인 SELECT. LOAD DATA·CSV를 쓰지 않는다. 같은 입력이면 바이트까지 같은 파일(시각·경로 없음).
값 바꾸기(2.3): 날짜 글 → DATE, 시각 글 → DATETIME(3) UTC(팀 V1 머리말), *_json → 글 그대로(JSON 형 칸이 받음), 0/1 그대로.

실행: python3 적재/export_mysql.py   (build/에서)
"""
import argparse
import hashlib
import json
import sqlite3
import sys
from datetime import datetime, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))  # build/: paths.py
import paths as P  # noqa: E402
KB_DB = P.KB_DB
LEDGER_DB = P.LEDGER_DB
CASE_DB = P.CASE_DB
OUT = P.MYSQL_OUT

BATCH_ROWS = 500
BATCH_BYTES = 1_000_000      # mysql 클라이언트 max_allowed_packet 기본 16MB보다 한참 작게(별표 글 하나가 최대 약 29KB)

# 값 바꾸기 대상 칸. 여기에 없는 칸은 SQLite 값을 그대로 쓴다.
DATE_COLS = {("kb_documents", "promulgation_date"), ("kb_documents", "effective_date"), ("kb_documents", "use_policy_at"),
             ("kb_chunks", "effective_from"), ("kb_chunks", "effective_to"), ("kb_annexes", "effective_from"),
             ("issue_packages", "decided_at"), ("ledger_versions", "published_on")}
DATETIME_COLS = {("kb_documents", "last_checked_at"), ("kb_documents", "created_at")}
JSON_COLS = {("kb_documents", "use_scope"), ("kb_documents", "excluded_pages"), ("guide_sections", "path"),
             ("issue_packages", "trigger_json"), ("issue_packages", "opened_by"), ("issue_packages", "rules_json"),
             ("issue_packages", "conditions_json"), ("issue_packages", "excluded_json"), ("package_items", "conds"),
             ("ledger_rows", "fields_json"), ("ledger_rows", "col_kinds_json"), ("ledger_rows", "pages_json"),
             ("ledger_rows", "merged_json"), ("ledger_rows", "flags_json"),
             ("fact_catalog", "ledger_attachments"), ("slot_catalog", "ask_condition")}

# 파일마다: 원천 DB, 테이블(INSERT 순서. DELETE는 거꾸로), 정렬. 정렬은 같은 입력이면 같은 파일이 되게 하는 것이다.
FILES = [
    ("10_kb.sql", "kb", [
        ("kb_documents", "doc_id"), ("guide_sections", "depth, doc_id, ord, section_id"), ("kb_chunks", "chunk_id"),
        ("kb_annexes", "annex_id"), ("kb_links", "link_id"), ("build_info", "k")]),
    ("20_packages.sql", "kb", [
        ("issue_packages", "version, package_id"), ("package_items", "item_id"), ("package_item_units", "item_id, ord")]),
    ("30_ledger.sql", "ledger", [
        ("ledger_versions", "version_id, attachment"), ("ledger_rows", "row_id"), ("ledger_segments", "segment_id"),
        ("ledger_unextracted", "attachment, page, kind, ref, text")]),
    ("40_case_catalog.sql", "case", [
        ("catalog_sources", "name"), ("fact_catalog", "ord"), ("fact_options", "fact_id, ord"),
        ("slot_catalog", "sort_order"), ("slot_options", "slot_key, ord")]),
]
SELF_REF = {"kb_documents": ("doc_id", "parent_doc_id"), "guide_sections": ("section_id", "parent_id")}
EXPECTED = {"kb": "privacyask.db", "ledger": "ledger.db", "case": "case.db"}


def sha256_file(p: Path) -> str:
    return hashlib.sha256(p.read_bytes()).hexdigest()


def q(name: str) -> str:
    return "`" + name + "`"


def string_literal(s: str) -> str:
    """MySQL 문자열 글자. 백슬래시 이스케이프가 켜진 기본 sql_mode 기준(파일 머리에서 NO_BACKSLASH_ESCAPES를 뺀다)."""
    return "'" + (s.replace("\\", "\\\\").replace("'", "\\'").replace("\0", "\\0").replace("\x1a", "\\Z")
                  .replace("\r", "\\r")) + "'"


def to_date(v):
    if v is None:
        return None
    datetime.strptime(v, "%Y-%m-%d")           # 형식이 다르면 여기서 멈춘다(조용히 넘기지 않음)
    return v


def to_datetime(v):
    """'YYYYMMDD' → 그날 00:00:00.000, ISO(+09:00) → UTC. 팀 V1: DATETIME(3)는 UTC."""
    if v is None:
        return None
    if len(v) == 8 and v.isdigit():
        d = datetime.strptime(v, "%Y%m%d")
    else:
        d = datetime.fromisoformat(v)
        if d.tzinfo is not None:
            d = d.astimezone(timezone.utc).replace(tzinfo=None)
    return d.strftime("%Y-%m-%d %H:%M:%S.") + f"{d.microsecond // 1000:03d}"


def convert(table, col, v):
    if v is None:
        return None
    if (table, col) in DATE_COLS:
        return to_date(v)
    if (table, col) in DATETIME_COLS:
        return to_datetime(v)
    if (table, col) in JSON_COLS:
        json.loads(v)                           # JSON 칸이 받을 수 있는 글인지 확인
    return v


def literal(v) -> str:
    if v is None:
        return "NULL"
    if isinstance(v, bool):
        return "1" if v else "0"
    if isinstance(v, int):
        return str(v)
    if isinstance(v, float):
        return repr(v)
    if isinstance(v, (bytes, bytearray)):
        return "X'" + bytes(v).hex() + "'"
    return string_literal(v)


def open_ro(p: Path) -> sqlite3.Connection:
    if not p.exists():
        sys.exit(f"{p} 없음: 먼저 db/build_db.py, ledger/build_ledger.py, case/store.py")
    con = sqlite3.connect(p.resolve().as_uri() + "?mode=ro", uri=True)
    return con


def parents_first(rows, cols, table):
    """자기 자신을 가리키는 칸(부모)이 있는 테이블: 부모 행이 먼저 오게 한다(FK가 켜져 있으므로)."""
    if table not in SELF_REF:
        return rows
    pk, parent = SELF_REF[table]
    ipk, ipar = cols.index(pk), cols.index(parent)
    by_id = {r[ipk]: r for r in rows}
    out, seen = [], set()

    def visit(r):
        if r[ipk] in seen:
            return
        seen.add(r[ipk])
        p = r[ipar]
        if p is not None and p in by_id:
            visit(by_id[p])
        out.append(r)
    for r in rows:
        visit(r)
    return out


def read_table(con, table, order):
    cols = [r[1] for r in con.execute(f"PRAGMA table_info({table})")]
    rows = con.execute(f"SELECT {', '.join(q(c) for c in cols)} FROM {table} ORDER BY {order}").fetchall()
    rows = parents_first(rows, cols, table)
    return cols, [tuple(convert(table, c, v) for c, v in zip(cols, r)) for r in rows]


def insert_statements(table, cols, rows):
    head = f"INSERT INTO {q(table)} ({', '.join(q(c) for c in cols)}) VALUES\n"
    batch, size = [], 0
    for r in rows:
        line = "(" + ",".join(literal(v) for v in r) + ")"
        nbytes = len(line.encode("utf-8")) + 2
        if batch and (len(batch) >= BATCH_ROWS or size + nbytes > BATCH_BYTES):
            yield head + ",\n".join(batch) + ";\n"
            batch, size = [], 0
        batch.append(line)
        size += nbytes
    if batch:
        yield head + ",\n".join(batch) + ";\n"


def check_select(counts, extra=()):
    parts = [f"SELECT '{t}' AS tbl, COUNT(*) AS actual, {n} AS expected, IF(COUNT(*) = {n}, 'OK', 'MISMATCH') AS ok FROM {q(t)}"
             for t, n in counts]
    parts += list(extra)
    return "\nUNION ALL\n".join(parts) + ";\n"


HEADER = """-- {name}: {desc}
-- export_mysql.py가 만든 파일. 손으로 고치지 않는다(원천이 바뀌면 다시 만든다). 같은 파일을 다시 돌리면 같은 결과다.
-- 실행: mysql -h 127.0.0.1 -u <계정> -p --default-character-set=utf8mb4 <DB> < {name}   (적재/README.md의 순서대로)
SET NAMES utf8mb4;
SET SESSION sql_mode = REPLACE(@@sql_mode, 'NO_BACKSLASH_ESCAPES', '');
"""

DESC = {
    "10_kb.sql": "법령·안내서 원문 D1~D7(문서, 절, 청크, 별표, 연결, 빌드 정보). 팀 V2의 Dev KB 스냅샷 행을 이 행으로 바꾼다(06 3.4)",
    "20_packages.sql": "쟁점 꾸러미 D6(v1·v2, 항목, 원문 단위)",
    "30_ledger.sql": "처리 원장 D8(판, 행, 조각, 못 뽑은 것)",
    "40_case_catalog.sql": "사안 기록 D9 기준표(출처 파일, 사실, 보기, 슬롯, 슬롯 값)",
}


def build_file(name, con, tables):
    data = {t: read_table(con, t, order) for t, order in tables}
    out = [HEADER.format(name=name, desc=DESC[name]), "START TRANSACTION;\n"]
    if name == "10_kb.sql":
        out.append(
            "-- 06 3.4 ① 팀 스냅샷이 가진 임베딩을 chunk_key로 잠시 옮겨 둔다(우리 안내서 청크는 chunk_key가 같다)\n"
            "DROP TEMPORARY TABLE IF EXISTS kb_emb_keep;\n"
            "CREATE TEMPORARY TABLE kb_emb_keep AS\n"
            "  SELECT chunk_key, embedding FROM kb_chunks WHERE chunk_key IS NOT NULL AND embedding IS NOT NULL;\n")
    out.append("-- 지우기(자식 먼저). FK는 켜 둔 채: 이 문서를 가리키는 다른 행(예: kb_update_proposals)이 있으면 여기서 멈추고 아무것도 안 남는다\n")
    for t, _ in reversed(tables):
        if t in SELF_REF:
            out.append(f"UPDATE {q(t)} SET {q(SELF_REF[t][1])} = NULL;\n")
        out.append(f"DELETE FROM {q(t)};\n")
    out.append("-- 넣기\n")
    for t, _ in tables:
        cols, rows = data[t]
        out.append(f"-- {t}: {len(rows)}행\n")
        out.extend(insert_statements(t, cols, rows))
    if name == "10_kb.sql":
        out.append(
            "-- 06 3.4 ④ 옮겨 둔 임베딩을 우리 행에 붙인다(우리 행에 이미 임베딩이 있으면 그대로 둔다)\n"
            "UPDATE kb_chunks c JOIN kb_emb_keep k ON c.chunk_key = k.chunk_key SET c.embedding = k.embedding WHERE c.embedding IS NULL;\n"
            "DROP TEMPORARY TABLE kb_emb_keep;\n")
    out.append("COMMIT;\n\n-- 행 수 확인: ok 칸이 모두 OK여야 한다\n")
    extra = []
    if name == "10_kb.sql":
        extra = ["SELECT 'kb_chunks.unit_key 빈 값' AS tbl, COUNT(*) AS actual, 0 AS expected, "
                 "IF(COUNT(*) = 0, 'OK', 'MISMATCH') AS ok FROM `kb_chunks` WHERE `unit_key` IS NULL OR `unit_key` = ''",
                 "SELECT 'kb_chunks.embedding 있는 청크(첫 적재=V2 안내서 1,253)' AS tbl, COUNT(*) AS actual, NULL AS expected, "
                 "'참고' AS ok FROM `kb_chunks` WHERE `embedding` IS NOT NULL"]
    out.append(check_select([(t, len(data[t][1])) for t, _ in tables], extra))
    return "".join(out), data


def max_chars(cols, rows):
    out = {}
    for i, c in enumerate(cols):
        m = max((len(r[i]) for r in rows if isinstance(r[i], str)), default=None)
        if m is not None:
            out[c] = m
    return out


def export(kb=KB_DB, ledger=LEDGER_DB, case=CASE_DB, out=OUT):
    srcs = {"kb": Path(kb), "ledger": Path(ledger), "case": Path(case)}
    cons = {k: open_ro(p) for k, p in srcs.items()}
    try:
        for k in ("kb", "ledger"):                 # FK를 끈 채 읽지 않았는지: 원천 자체에 FK 위반이 없어야 MySQL에서도 안 걸린다
            bad = cons[k].execute("PRAGMA foreign_key_check").fetchall()
            if bad:
                sys.exit(f"{srcs[k].name}에 FK 위반 {len(bad)}건: {bad[:3]}")
        out = Path(out)
        out.mkdir(parents=True, exist_ok=True)
        manifest = {"format": 1, "sources": {}, "files": {}, "tables": {}}
        for k, p in srcs.items():
            manifest["sources"][EXPECTED[k]] = {"sha256": sha256_file(p)}
        kbc = cons["kb"]
        manifest["package_versions"] = [r[0] for r in kbc.execute("SELECT DISTINCT version FROM issue_packages ORDER BY version")]
        manifest["package_current"] = dict(kbc.execute("SELECT k, v FROM build_info")).get("package_current")
        manifest["ledger_versions"] = [r[0] for r in cons["ledger"].execute("SELECT DISTINCT version_id FROM ledger_versions ORDER BY 1")]
        manifest["catalog_sources"] = {r[0]: {"version": r[1], "sha256": r[2]} for r in cons["case"].execute(
            "SELECT name, version, sha256 FROM catalog_sources ORDER BY name")}
        manifest["fact_map_sha256"] = manifest["catalog_sources"]["fact_map"]["sha256"]
        total = 0
        for name, src, tables in FILES:
            text, data = build_file(name, cons[src], tables)
            (out / name).write_text(text, encoding="utf-8", newline="\n")
            b = text.encode("utf-8")
            manifest["files"][name] = {"sha256": hashlib.sha256(b).hexdigest(), "bytes": len(b), "source": EXPECTED[src],
                                       "tables": [t for t, _ in tables]}
            for t, _ in tables:
                cols, rows = data[t]
                manifest["tables"][t] = {"file": name, "rows": len(rows), "columns": cols, "max_chars": max_chars(cols, rows)}
                total += len(rows)
        manifest["total_rows"] = total
        (out / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=1, sort_keys=True) + "\n",
                                           encoding="utf-8", newline="\n")
        return manifest
    finally:
        for c in cons.values():
            c.close()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--kb", default=str(KB_DB))
    ap.add_argument("--ledger", default=str(LEDGER_DB))
    ap.add_argument("--case", default=str(CASE_DB))
    ap.add_argument("--out", default=str(OUT))
    a = ap.parse_args()
    m = export(a.kb, a.ledger, a.case, a.out)
    for name, f in m["files"].items():
        n = sum(m["tables"][t]["rows"] for t in f["tables"])
        print(f"{name}: {n}행, {f['bytes']:,}바이트")
    print(f"합계 {m['total_rows']}행 → {a.out}")


if __name__ == "__main__":
    main()
