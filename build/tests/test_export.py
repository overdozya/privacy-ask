"""MySQL 적재 파일(적재/export_mysql.py) 테스트. 실행: python3 -m unittest tests.test_export -v  (build/에서)

- manifest의 테이블별 행 수 = SQLite 행 수, 그리고 SQL 파일에서 다시 읽어 센 행 수도 같다(완료 기준 2)
- SQL을 MySQL 규칙(백슬래시 이스케이프)으로 직접 읽어, 바꾸지 않는 칸은 SQLite 값과 글자까지 같다
- 바꾸는 칸(날짜·시각)은 형식, 4개 파일의 틀(DELETE 순서), 규칙 3테이블(06 2.4·2.5), 개발용 90_reset_cases.sql
MySQL은 띄우지 않는다. privacyask.db·ledger.db·case.db가 없으면 건너뛴다.
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
sys.path.insert(0, str(ROOT / "적재"))
import export_mysql as ex  # noqa: E402



# ───────── MySQL 글자 읽기(검증용. export_mysql.py의 쓰기 코드와 따로 만든 읽기) ─────────

ESC = {"0": "\0", "n": "\n", "r": "\r", "t": "\t", "Z": "\x1a", "b": "\b", "\\": "\\", "'": "'", '"': '"'}
NUM = re.compile(r"-?\d+(\.\d+)?([eE][-+]?\d+)?")


def statements(text):
    """따옴표 밖의 ;로 문장을 나누고, 따옴표 밖 줄머리의 -- 주석 줄을 뺀다."""
    out, cur = [], []
    i, n, in_q, bol = 0, len(text), False, True
    while i < n:
        ch = text[i]
        if in_q:
            cur.append(ch)
            if ch == "\\":
                cur.append(text[i + 1])
                i += 1
            elif ch == "'":
                in_q = False
            bol = False
        elif bol and text.startswith("--", i):
            j = text.find("\n", i)
            i = n if j < 0 else j       # 줄바꿈 글자는 아래에서 처리
            continue
        elif ch == "'":
            in_q = True
            cur.append(ch)
            bol = False
        elif ch == ";":
            s = "".join(cur).strip()
            if s:
                out.append(s)
            cur = []
            bol = False
        else:
            cur.append(ch)
            bol = ch == "\n"
        i += 1
    assert not in_q
    s = "".join(cur).strip()
    if s:
        out.append(s)
    return out


def parse_values(s):
    """VALUES 뒤 '(…),(…)' → [[값,…],…]. NULL → None, 숫자 → int/float, X'..' → bytes, '…' → str."""
    rows, i, n = [], 0, len(s)
    while i < n:
        while i < n and s[i] in " \n,":
            i += 1
        if i >= n:
            break
        assert s[i] == "(", s[i:i + 30]
        i += 1
        row = []
        while True:
            if s[i] == "'":
                j, buf = i + 1, []
                while s[j] != "'":
                    if s[j] == "\\":
                        buf.append(ESC[s[j + 1]])
                        j += 2
                    else:
                        buf.append(s[j])
                        j += 1
                row.append("".join(buf))
                i = j + 1
            elif s.startswith("NULL", i):
                row.append(None)
                i += 4
            elif s.startswith("X'", i):
                j = s.index("'", i + 2)
                row.append(bytes.fromhex(s[i + 2:j]))
                i = j + 1
            else:
                m = NUM.match(s, i)
                v = m.group(0)
                row.append(float(v) if ("." in v or "e" in v.lower()) else int(v))
                i = m.end()
            if s[i] == ",":
                i += 1
                continue
            assert s[i] == ")", s[i:i + 30]
            i += 1
            break
        rows.append(row)
    return rows


def parse_file(text):
    """파일 → (삽입 {테이블: (칸, 행들)}, 문장 목록)."""
    ins, stmts = {}, statements(text)
    for st in stmts:
        m = re.match(r"INSERT INTO `(\w+)` \((.*?)\) VALUES\n", st, re.S)
        if m:
            cols = re.findall(r"`(\w+)`", m.group(2))
            c, rows = ins.setdefault(m.group(1), (cols, []))
            assert c == cols
            rows.extend(parse_values(st[m.end():]))
    return ins, stmts


class Literals(unittest.TestCase):
    def test_roundtrip_special_characters(self):
        for s in ["", "a'b", 'a"b', "a\\b", "\\n", "줄\n바꿈", "탭\t", "널\0글자", "\x1a", "\\'", "'';--", "ⅠⅡ ㆍ ※ 상자 ┌─┐", "a\rb"]:
            self.assertEqual(parse_values("(" + ex.literal(s) + ")"), [[s]], repr(s))
        self.assertEqual(parse_values("(" + ",".join(map(ex.literal, [None, 3, -2, 0.5, b"\x00\xff"])) + ")"),
                         [[None, 3, -2, 0.5, b"\x00\xff"]])

    def test_dates(self):
        self.assertEqual(ex.to_date("2026-11-26"), "2026-11-26")
        for bad in ("20261126", "2026-13-01", "2026-11-26T00:00"):
            with self.assertRaises(ValueError):
                ex.to_date(bad)
        self.assertEqual(ex.to_datetime("20261125"), "2026-11-25 00:00:00.000")
        self.assertEqual(ex.to_datetime("2026-11-25T18:15:48.277144+09:00"), "2026-11-25 09:15:48.277")   # UTC
        self.assertEqual(ex.to_datetime("2026-11-25T00:30:00+09:00"), "2026-11-24 15:30:00.000")

    def test_json_must_parse(self):
        with self.assertRaises(ValueError):
            ex.convert("kb_documents", "use_scope", "[깨진")


def need_dbs():
    for p in (ex.KB_DB, ex.LEDGER_DB, ex.CASE_DB):
        if not p.exists():
            raise unittest.SkipTest(f"{p} 없음")


class Export(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        need_dbs()
        cls.tmp = tempfile.TemporaryDirectory()
        cls.out = Path(cls.tmp.name)
        cls.m = ex.export(out=cls.out)
        cls.parsed, cls.stmts = {}, {}
        for name, *_ in ex.FILES:
            cls.parsed[name], cls.stmts[name] = parse_file((cls.out / name).read_text(encoding="utf-8"))
        cls.src = {"kb": sqlite3.connect(ex.KB_DB), "ledger": sqlite3.connect(ex.LEDGER_DB), "case": sqlite3.connect(ex.CASE_DB)}

    @classmethod
    def tearDownClass(cls):
        for c in cls.src.values():
            c.close()
        cls.tmp.cleanup()

    def test_files_exist(self):
        want = ["10_kb.sql", "20_packages.sql", "30_ledger.sql", "40_case_catalog.sql", "90_reset_cases.sql", "manifest.json"]
        if (P.BUNDLE / "emb").is_dir():
            want.insert(1, "15_kb_embeddings.sql")   # S10b: 안내서 벡터(build/emb/align_vectors.py)
        self.assertEqual(sorted(p.name for p in self.out.iterdir()), want)
        self.assertEqual(json.loads((self.out / "manifest.json").read_text(encoding="utf-8")), self.m)

    @unittest.skipUnless((P.BUNDLE / "emb").is_dir(), "확정 묶음 emb 없음")
    def test_embeddings_in_manifest(self):
        """S10b: export가 끝에 벡터 적재 파일을 쓰고 manifest에 그 칸을 남긴다(다시 export해도 빠지지 않음)."""
        f = self.m["files"]["15_kb_embeddings.sql"]
        self.assertEqual(f["after"], "10_kb.sql")
        self.assertEqual(f["sha256"], hashlib.sha256((self.out / "15_kb_embeddings.sql").read_bytes()).hexdigest())
        e = self.m["embeddings"]
        guide = self.src["kb"].execute("SELECT COUNT(*) FROM kb_chunks c JOIN kb_documents d USING(doc_id) "
                                       "WHERE d.source_type='GUIDE' AND c.chunk_key IS NOT NULL").fetchone()[0]
        self.assertEqual(e["rows"], guide)
        self.assertEqual(e["matched_bundle"] + e["new"], guide)

    def test_manifest_rows_equal_sqlite_rows(self):
        """완료 기준 2: manifest 행 수 = SQLite 행 수 = SQL 파일에서 다시 읽어 센 행 수."""
        total = 0
        for name, src, tables in ex.FILES:
            for t, _ in tables:
                n = self.src[src].execute(f"SELECT count(*) FROM {t}").fetchone()[0]
                self.assertEqual(self.m["tables"][t]["rows"], n, t)
                self.assertEqual(len(self.parsed[name][t][1]), n, f"{name} {t}")
                total += n
        self.assertEqual(self.m["total_rows"], total)

    def test_values_survive_unchanged(self):
        """바꾸지 않는 칸은 SQLite 값과 글자까지 같다."""
        changed = ex.DATE_COLS | ex.DATETIME_COLS
        for name, src, tables in ex.FILES:
            for t, order in tables:
                cols, rows = self.parsed[name][t]
                self.assertEqual(cols, [r[1] for r in self.src[src].execute(f"PRAGMA table_info({t})")], t)
                want = self.src[src].execute(f"SELECT {', '.join(ex.q(c) for c in cols)} FROM {t} ORDER BY {order}").fetchall()
                if t in ex.SELF_REF:                       # 부모 먼저 순서로 바뀌었을 수 있다 → 키로 맞춤
                    key = cols.index(ex.SELF_REF[t][0])
                    want = sorted(want, key=lambda r: str(r[key]))
                    rows = sorted(rows, key=lambda r: str(r[key]))
                for i, c in enumerate(cols):
                    if (t, c) in changed:
                        continue
                    self.assertEqual([r[i] for r in rows], [w[i] for w in want], f"{t}.{c}")

    def test_date_columns_format(self):
        seen = 0
        for name, *_ in ex.FILES:
            for t, (cols, rows) in self.parsed[name].items():
                for i, c in enumerate(cols):
                    for v in (r[i] for r in rows):
                        if v is None:
                            continue
                        if (t, c) in ex.DATE_COLS:
                            self.assertRegex(v, r"^\d{4}-\d\d-\d\d$", f"{t}.{c}")
                            seen += 1
                        if (t, c) in ex.DATETIME_COLS:
                            self.assertRegex(v, r"^\d{4}-\d\d-\d\d \d\d:\d\d:\d\d\.\d{3}$", f"{t}.{c}")
                            seen += 1
        self.assertGreater(seen, 1000)

    def test_parents_come_first(self):
        for t in ("kb_documents", "guide_sections"):
            cols, rows = self.parsed["10_kb.sql"][t]
            pk, par = ex.SELF_REF[t]
            seen = set()
            for r in rows:
                p = r[cols.index(par)]
                self.assertTrue(p is None or p in seen, f"{t} {r[cols.index(pk)]}의 부모 {p}가 뒤에 있음")
                seen.add(r[cols.index(pk)])

    def test_file_frame(self):
        """START TRANSACTION → DELETE(자식 먼저) → INSERT(테이블 순서) → COMMIT → 확인 SELECT. LOAD DATA 없음."""
        for name, src, tables in ex.FILES:
            kinds = [re.match(r"(SET|START TRANSACTION|DROP|CREATE|UPDATE|DELETE FROM `\w+`|INSERT INTO `\w+`|COMMIT|SELECT)", s).group(1)
                     for s in self.stmts[name]]
            self.assertEqual(kinds[:3], ["SET", "SET", "START TRANSACTION"])
            self.assertEqual(kinds[-2:], ["COMMIT", "SELECT"])
            self.assertEqual(kinds.count("COMMIT"), 1)
            dels = [k.split("`")[1] for k in kinds if k.startswith("DELETE")]
            self.assertEqual(dels, [t for t, _ in reversed(tables)], name)
            first_insert = next(i for i, k in enumerate(kinds) if k.startswith("INSERT"))
            self.assertLess(max(i for i, k in enumerate(kinds) if k.startswith("DELETE")), first_insert)
            order = []
            for k in kinds:
                if k.startswith("INSERT") and (not order or order[-1] != k.split("`")[1]):
                    order.append(k.split("`")[1])
            self.assertEqual(order, [t for t, _ in tables], name)
            self.assertNotIn("LOAD DATA", (self.out / name).read_text(encoding="utf-8"))

    def test_kb_chunks_no_embedding_carry(self):
        """06 2.5: 빈 DB에 넣으므로 임베딩 옮기기(kb_emb_keep)를 하지 않는다. 우리 행은 임베딩 NULL, 안내서 청크만 chunk_key."""
        self.assertNotIn("kb_emb_keep", (self.out / "10_kb.sql").read_text(encoding="utf-8"))
        cols, rows = self.parsed["10_kb.sql"]["kb_chunks"]
        self.assertTrue(all(r[cols.index("embedding")] is None for r in rows))
        self.assertEqual(sum(r[cols.index("chunk_key")] is not None for r in rows), 3925)
        self.assertTrue(all(r[cols.index("unit_key")] for r in rows))

    def test_rule_tables_in_catalog_file(self):
        """06 2.4: 40번 파일에 규칙 3테이블. 행 = 현업 규칙 세트 1·위험 규칙 16·슬롯 변환 규칙 파일의 규칙 수, 출처 행 3개."""
        tables = [t for t, _ in dict((n, tb) for n, _, tb in ex.FILES)["40_case_catalog.sql"]]
        self.assertEqual(tables[-3:], ["rule_sets", "risk_rules", "slot_rules"])
        rules = json.loads(P.SLOT_RULES.read_text(encoding="utf-8"))
        want = {"rule_sets": 1, "risk_rules": 16, "slot_rules": sum(len(v) for v in rules["slots"].values()), "catalog_sources": 3}
        self.assertEqual({t: self.m["tables"][t]["rows"] for t in want}, want)
        self.assertEqual(self.m["catalog_sources"]["slot_rules"]["sha256"], ex.sha256_file(P.SLOT_RULES))
        cols, rows = self.parsed["40_case_catalog.sql"]["slot_rules"]
        for r in rows:                                   # JSON 칸은 MySQL JSON 형이 받으므로 글이 JSON이어야 한다
            for c in ("when_json", "values_json", "basis_keys"):
                json.loads(r[cols.index(c)])
            self.assertIn(r[cols.index("effect")], ("SET", "ADD", "SKIP"))
            self.assertTrue(r[cols.index("note")].startswith("[04 "))

    def test_reset_file(self):
        """90_reset_cases.sql: D9 12테이블만 자식 먼저 지우고(답 턴이 먼저), 기준표·지식·규칙은 건드리지 않는다."""
        text = (self.out / ex.RESET_FILE).read_text(encoding="utf-8")
        dels = re.findall(r"^DELETE FROM `(\w+)`", text, re.M)
        self.assertEqual(dels, ex.RESET_TABLES)
        self.assertIn("DELETE FROM `turns` ORDER BY turn_no DESC;", text)
        kept = {t for _, _, tb in ex.FILES for t, _ in tb}
        self.assertEqual(set(dels) & kept, set())
        self.assertEqual(text.count("START TRANSACTION"), 1)
        self.assertEqual(self.m["reset"][ex.RESET_FILE]["sha256"], hashlib.sha256(text.encode("utf-8")).hexdigest())
        if P.MIGRATION.exists():                         # S2의 V5·V6이 있으면 D9 테이블이 그 파일의 테이블과 같다
            ddl = "".join(f.read_text(encoding="utf-8") for f in sorted(P.MIGRATION.glob("V[56]__*.sql")))
            if ddl:
                self.assertEqual(set(re.findall(r"CREATE TABLE `?(\w+)`?", ddl)), set(ex.RESET_TABLES))

    def test_batches_are_small(self):
        for name, *_ in ex.FILES:
            for s in self.stmts[name]:
                if s.startswith("INSERT"):
                    rows = len(parse_values(s[s.index("VALUES\n") + 7:]))
                    self.assertLessEqual(rows, ex.BATCH_ROWS)
                    if rows > 1:
                        self.assertLessEqual(len(s.encode("utf-8")), ex.BATCH_BYTES + 400)   # 머리글 몫

    def test_manifest_contents(self):
        m = self.m
        self.assertEqual(set(m["sources"]), {"privacyask.db", "ledger.db", "case.db"})
        self.assertTrue(all(re.fullmatch(r"[0-9a-f]{64}", v["sha256"]) for v in m["sources"].values()))
        self.assertEqual(m["package_versions"], ["v1", "v2"])
        self.assertEqual(m["package_current"], "v2")
        self.assertEqual(m["ledger_versions"], ["20261028"])
        self.assertEqual(m["fact_map_sha256"], m["catalog_sources"]["fact_map"]["sha256"])
        self.assertEqual(m["fact_map_sha256"], ex.sha256_file(P.FACT_MAP))
        for name, f in m["files"].items():
            self.assertEqual(f["sha256"], hashlib.sha256((self.out / name).read_bytes()).hexdigest())

    def test_known_counts(self):
        """06 3.2 표의 행 수(지금 SQLite). 빌드가 바뀌면 이 숫자도 06과 함께 본다."""
        want = {"kb_documents": 50, "guide_sections": 3474, "kb_chunks": 12920, "kb_annexes": 94, "kb_links": 14728,
                "issue_packages": 28, "package_items": 576, "package_item_units": 935,
                "ledger_rows": 1494, "ledger_segments": 7212, "ledger_unextracted": 5,
                "fact_catalog": 46, "fact_options": 162, "slot_catalog": 8, "slot_options": 29, "catalog_sources": 3,
                "rule_sets": 1, "risk_rules": 16}
        self.assertEqual({t: self.m["tables"][t]["rows"] for t in want}, want)

    def test_ledger_row_hash_survives_mysql_json(self):
        """원장 인용 해시(store.ledger_row_hash)는 fields_json 글자의 sha256이다. MySQL JSON 칸은 글을 JSON 정규 모양으로 다시 쓰므로
        (', ' 구분, 한글 그대로) 지금 글이 이미 그 모양이어야 해시가 MySQL에서도 같다. 아니면 fields_json은 MEDIUMTEXT로 둔다."""
        for (v,) in self.src["ledger"].execute("SELECT fields_json FROM ledger_rows"):
            self.assertEqual(json.dumps(json.loads(v), ensure_ascii=False), v)


if __name__ == "__main__":
    unittest.main()
