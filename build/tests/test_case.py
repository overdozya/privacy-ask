"""사안 기록(D9, 4b단계) 테스트. 실행: python3 -m unittest tests.test_case -v  (build/에서)

- 기준표: 사실·허용 값은 04 대응표 tsv, 슬롯 값은 현업 rule_set_v1.0.json에서 읽은 그대로(완료 기준 1·2)
- DDL: SQLite·MySQL 두 판의 테이블·칸이 같다, SQLite 전용 기능을 안 쓴다(완료 기준 1)
- 거부 규칙 C01~C17: 규칙마다 저장 함수가 거부하고 아무것도 남기지 않는다. 칸 제약으로 되는 것은 DB도 막는다(완료 기준 2)
- 예시 사안: A0~A8 재현표가 사안 DB만으로 나오고, 다시 만들면 바이트까지 같고, 지식 DB 두 개는 안 바뀐다(완료 기준 3·4)
privacyask.db·ledger.db가 없으면 먼저 python3 db/build_db.py, python3 ledger/build_ledger.py
"""
import csv
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
sys.path.insert(0, str(ROOT / "case"))
import replay as rp  # noqa: E402
import store as st  # noqa: E402

CID = "00000000-0000-4000-8000-0000000000aa"
REQ = "E0001"
PV = "1@0a1b2c3d"      # 프롬프트 버전 형식: <버전>@<sha256 앞 8자>
T = "2026-11-26 09:00:00.000"
CONTENT = "회원 휴대폰 번호를 국내 발송 업체에 맡기려고 합니다. 업체 서버 위치는 아직 모르겠어요."
H15 = "8adbf89d706b68906b5a335c398bc16fbc17add78464fec1f0a59b02cb6974f4"   # 개인정보보호법|15|1|| 현행
H26 = "06c26a45d013bdb37e103dfb4ae7678581aa696e5fde361a7257b8b3ccfbd072"   # 개인정보보호법|26|1|| 현행


def sha(p):
    return hashlib.sha256(Path(p).read_bytes()).hexdigest()


def need_dbs():
    for p in (st.KB_DB, st.LEDGER_DB):
        if not p.exists():
            raise unittest.SkipTest(f"{p} 없음")


class Catalog(unittest.TestCase):
    """기준표는 입력 파일에서 그대로 온다(손으로 옮겨 적지 않음)."""

    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        cls.db = Path(cls.tmp.name) / "c.db"
        st.create_db(cls.db)
        cls.c = sqlite3.connect(cls.db)
        with st.FACT_MAP.open(encoding="utf-8", newline="") as f:
            cls.tsv = list(csv.DictReader(f, delimiter="\t"))

    @classmethod
    def tearDownClass(cls):
        cls.c.close()
        cls.tmp.cleanup()

    def test_facts_are_tsv_rows(self):
        got = [r[0] for r in self.c.execute("SELECT fact_id FROM fact_catalog ORDER BY ord")]
        self.assertEqual(got, [r["사실ID"] for r in self.tsv])
        self.assertEqual(len(got), 46)
        for r in self.tsv:
            raw = self.c.execute("SELECT allowed_raw, question FROM fact_catalog WHERE fact_id=?", (r["사실ID"],)).fetchone()
            self.assertEqual(raw, (r["허용 값"], r["묻는 말(현업 담당자에게)"]))

    def test_option_kinds(self):
        kinds = dict(self.c.execute("SELECT kind, count(*) FROM fact_options GROUP BY kind"))
        self.assertEqual(kinds, {"CHOICE": 101, "UNKNOWN": 41, "MATCH": 8, "DETAIL": 7, "NO_MATCH": 5})
        detail = self.c.execute("SELECT fact_id, label FROM fact_options WHERE kind='DETAIL' ORDER BY fact_id, ord").fetchall()
        self.assertEqual(detail, [("F-B2", "예(법령·조문)"), ("F-B5", "예(이익 적기)"), ("F-C1", "예(나라)"), ("F-C2", "예(나라)"),
                                  ("F-S1", "서비스명(자유 입력)"), ("F-S2", "흐름 목록(자유 입력, A1이 흐름별로 나눔)"), ("F-S3", "날짜")])
        multi = [r[0] for r in self.c.execute("SELECT fact_id FROM fact_catalog WHERE is_multi=1 ORDER BY ord")]
        self.assertEqual(multi, ["F-D1", "F-D3", "F-AD1"])

    def test_option_labels_are_in_tsv(self):
        """보기 글자가 tsv 허용 값 칸(펼친 것은 질문 문장) 안에 그대로 있다."""
        for fid, label in self.c.execute("SELECT fact_id, label FROM fact_options"):
            allowed, q = self.c.execute("SELECT allowed_raw, question FROM fact_catalog WHERE fact_id=?", (fid,)).fetchone()
            self.assertTrue(label in allowed or label in q, (fid, label))

    def test_ledger_attachments(self):
        att = dict(self.c.execute("SELECT fact_id, ledger_attachments FROM fact_catalog"))
        self.assertEqual(json.loads(att["L1"]), ["첨부1", "첨부2"])
        self.assertEqual(json.loads(att["F-T1"]), ["첨부4", "첨부5"])   # '원장 조회' 칸 = L2
        self.assertEqual(json.loads(att["L4"]), ["첨부10"])
        self.assertEqual(json.loads(att["F-T3"]), [])

    def test_slots_are_seed(self):
        rs = json.loads(st.RULE_SET.read_text(encoding="utf-8"))
        judge = [s for s in rs["slot_dictionary"] if s["purpose"] == "JUDGE"]
        self.assertEqual(len(judge), 8)
        for s in judge:
            vals = [r[0] for r in self.c.execute("SELECT value FROM slot_options WHERE slot_key=? ORDER BY ord", (s["slot_key"],))]
            self.assertEqual(vals, s["allowed_values"])
            self.assertEqual(self.c.execute("SELECT value_type FROM slot_catalog WHERE slot_key=?", (s["slot_key"],)).fetchone()[0], s["value_type"])

    def test_sources_hash(self):
        src = dict(self.c.execute("SELECT name, sha256 FROM catalog_sources"))
        self.assertEqual(src, {"fact_map": sha(st.FACT_MAP), "rule_set": sha(st.RULE_SET)})


def tables(sql_text):
    """CREATE TABLE 이름 → 칸 이름 목록(제약 줄 제외)."""
    out = {}
    for name, body in re.findall(r"CREATE TABLE (\w+) \((.*?)\n\)", sql_text, re.S):
        cols = []
        for line in body.split("\n"):
            m = re.match(r"\s+(\w+)\s+(?:CHAR|VARCHAR|TEXT|INTEGER|INT|BIGINT|REAL|DECIMAL|JSON|DATE|DATETIME|TINYINT)", line)
            if m and m.group(1) not in ("PRIMARY", "UNIQUE", "FOREIGN", "CONSTRAINT", "KEY", "CHECK"):
                cols.append(m.group(1))
        out[name] = cols
    return out


class Schema(unittest.TestCase):
    def setUp(self):
        strip = lambda t: re.sub(r"--[^\n]*", "", t)   # 주석 빼고 DDL만 본다
        self.lite = strip((ROOT / "case" / "schema.sql").read_text(encoding="utf-8"))
        self.my = strip((ROOT / "case" / "schema_mysql.sql").read_text(encoding="utf-8"))

    def test_same_tables_and_columns(self):
        a, b = tables(self.lite), tables(self.my)
        self.assertEqual(list(a), list(b))
        self.assertEqual(len(a), 17)
        for t in a:
            self.assertEqual(a[t], b[t], t)
        self.assertIn("CREATE VIEW facts_current", self.lite)
        self.assertIn("CREATE VIEW facts_current", self.my)
        for t in ("cases", "flows", "facts", "turns", "case_judgments"):   # 계획 D9의 다섯 테이블
            self.assertIn(t, a)

    def test_judgment_tables_renamed(self):
        """판정 테이블 5개는 case_ 접두. 팀 judgments·judgment_issues와 같은 이름·같은 제약 이름이 없다(06 2.4-1, F1)."""
        mine = tables(self.my)
        want = ["case_judgments", "case_judgment_slots", "case_judgment_slot_values", "case_judgment_issues", "case_judgment_citations"]
        for n in want:
            self.assertIn(n, mine)
        for bad in ("judgments", "judgment_slots", "judgment_slot_values", "judgment_issues", "judgment_citations"):
            self.assertNotIn(bad, mine)
        v1 = P.TEAM_V1
        if not v1.exists():
            self.skipTest("팀 V1 없음")
        team = v1.read_text(encoding="utf-8")
        team_tables = set(re.findall(r"CREATE TABLE `?(\w+)`?", team))
        self.assertEqual(set(mine) & team_tables, set(), "팀 테이블과 이름이 겹침")
        mine_names = set(re.findall(r"CONSTRAINT (\w+)", self.my)) | set(re.findall(r"(?:UNIQUE )?KEY (\w+) \(", self.my))
        team_names = set(re.findall(r"CONSTRAINT `?(\w+)`?", team))
        self.assertEqual(mine_names & team_names, set(), "팀 제약 이름과 겹침")

    def test_case_pins_status_attempts_collation(self):
        lite, my = tables(self.lite), tables(self.my)
        for t in (lite, my):
            for c in ("requester_id", "rule_set_version", "package_version", "ledger_version"):
                self.assertIn(c, t["cases"])
            self.assertNotIn("flow_type", t["flows"])           # 2.4-6: 흐름 유형 칸을 두지 않는다
            self.assertEqual({"label", "origin", "destination", "service_name"} & set(t["flows"]), {"label", "origin", "destination", "service_name"})
            self.assertFalse({"type", "kind", "flow_type", "flow_kind"} & set(t["flows"]))
        for sql in (self.lite, self.my):
            self.assertIn("'CONFIRMED','MATCHED','INFERRED','NOT_FOUND','UNKNOWN'", sql)
            self.assertIn("attempts IN (1,2,3)", sql)
        for col in ("fact_options", "fact_values", "slot_options", "case_judgment_slot_values"):   # 06 2.3: 허용 값 칸은 이진 비교
            m = re.search(r"CREATE TABLE %s \((.*?)\n\)" % col, self.my, re.S).group(1)
            self.assertIn("COLLATE utf8mb4_bin", m, col)
        self.assertNotIn("COLLATE utf8mb4_bin", self.lite)       # SQLite는 기본이 이진 비교

    def test_no_sqlite_only_features(self):
        for bad in ("WITHOUT ROWID", "STRICT", "AUTOINCREMENT", "GLOB", "typeof("):
            self.assertNotIn(bad, self.lite)
        for idx in re.findall(r"CREATE INDEX[^;]*;", self.lite):
            self.assertNotIn(" WHERE ", idx)          # 부분 인덱스 없음
        for word in (" usage ", " condition ", " rows "):   # MySQL 예약어를 칸 이름으로 쓰지 않음
            self.assertNotIn(word, self.my.lower().replace("(", " ").replace(",", " "))


class Fixture(unittest.TestCase):
    """빈 사안 + 단계 3개(A1 LLM, A3 CODE, A8 CODE) + 흐름 1개."""

    @classmethod
    def setUpClass(cls):
        need_dbs()

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.path = Path(self.tmp.name) / "c.db"
        st.create_db(self.path)
        self.s = st.Store(self.path)
        self.s.add_case(CID, CONTENT, T, REQ, "v1.0", "v2", "20261028")
        self.a1 = self.s.add_step(CID, "A1", "LLM", "추출", {}, {}, T, prompt_id="p", prompt_version=PV, model_name="m", temperature=0.0)
        self.a3 = self.s.add_step(CID, "A3", "CODE", "원장", {}, {}, T)
        self.a8 = self.s.add_step(CID, "A8", "CODE", "기록", {}, {}, T)
        self.s.add_flow(CID, 1, "흐름", "회사", "업체", self.a1)

    def tearDown(self):
        self.s.close()
        self.tmp.cleanup()

    def count(self, table):
        return self.s.one(f"SELECT count(*) FROM {table}")[0]

    def rejects(self, code, fn, *a, **kw):
        before = {t: self.count(t) for t in ("facts", "fact_values", "fact_ledger_rows", "turns", "case_judgments", "case_judgment_slots",
                                             "case_judgment_slot_values", "case_judgment_issues", "case_judgment_citations", "steps")}
        with self.assertRaises(st.Rejected) as cm:
            fn(*a, **kw)
        self.assertEqual(cm.exception.code, code, str(cm.exception))
        self.assertEqual(before, {t: self.count(t) for t in before}, "거부했는데 행이 남음")

    def fact(self, fid="F-T1", values=("예",), status="CONFIRMED", source="INQUIRY", flow=1, **kw):
        kw.setdefault("quote", "국내 발송 업체에 맡기려고")
        step = kw.pop("step", self.a3 if source == "LEDGER" else self.a1)
        return self.s.add_fact(CID, fid, list(values), status, source, step, T, flow_no=flow, **kw)

    def ledger_fact(self, **kw):
        kw.setdefault("values", ("명시 일치",))
        kw.setdefault("status", "MATCHED")
        kw.setdefault("rows", ["첨부1-0040"])
        kw.setdefault("ledger_version", "20261028")
        kw.setdefault("quote", "멤버십 관련 고지사항 전달")
        rows = kw.pop("rows")
        return self.fact("L1", source="LEDGER", ledger_rows=rows, **kw)

    def judgment(self, **over):
        slots = []
        for k, vt in self.s.con.execute("SELECT slot_key, value_type FROM slot_catalog ORDER BY sort_order"):
            v = self.s.one("SELECT value FROM slot_options WHERE slot_key=? ORDER BY ord", k)[0]
            slots.append(dict(flow=1, slot=k, status="CONFIRMED", values=[v], basis_facts=[], unknown_facts=[]))
        kw = dict(step_seq=self.a8, reference_date="2026-12-01", rule_set_version="v1.0", package_version="v2",
                  fact_map_sha256=sha(st.FACT_MAP), model_name="m", temperature=0.0,
                  prompt_versions=[{"prompt_id": "p", "version": PV}], case_grade="M", exit_type="TRANSFER",
                  exit_reasons=["중"], conclusive=True, flags={}, created_at=T, review_bundle={}, slots=slots,
                  issues=[dict(flow=1, issue="처리위탁", package_id="PKG04", grade="M", rule_ids=["R-P03"], conditions=["C1"],
                               interp_flags=[], conclusive=True, visible=False)],
                  citations=[dict(issue=0, kind="LAW_UNIT", key="개인정보보호법|26|1||", text_hash=H26, origin="PACKAGE")])
        kw.update(over)
        return self.s.add_judgment(CID, **kw)

    def raw(self, sql, *a):
        """저장 함수를 거치지 않은 쓰기(예: 다른 언어 구현의 실수). 칸 제약이 막아야 한다."""
        with self.assertRaises(sqlite3.IntegrityError):
            with self.s.con:
                self.s.con.execute(sql, a)


class Accept(Fixture):
    def test_valid_writes(self):
        self.fact()
        self.fact("F-C1", [["예(나라)", "베트남"]], quote="국내 발송 업체")
        self.ledger_fact()
        self.fact("L1", ["없음"], source="LEDGER", status="INFERRED", ledger_version="20261028", quote=None)
        self.fact("L1", ["포괄 일치"], source="LEDGER", status="INFERRED", ledger_version="20261028", ledger_rows=["첨부1-0040"],
                  quote="멤버십 관련 고지사항 전달")
        jid = self.judgment(ledger_version="20261028")
        self.assertEqual(jid, 1)
        self.assertEqual(self.count("case_judgment_slot_values"), 8)

    def test_rev_stacks(self):
        self.fact("F-C2", [], status="NOT_FOUND", quote="업체 서버 위치는 아직 모르겠어요")
        q = self.s.add_turn(CID, "AGENT", "QUESTION", "서버가 해외에?", ["예(나라)", "아니오", "잘 모르겠음"], self.a1, T, flow_no=1, fact_id="F-C2", ask_no=1)
        a = self.s.add_turn(CID, "USER", "ANSWER", "잘 모르겠음", ["잘 모르겠음"], self.a1, T, reply_to=q)
        self.fact("F-C2", ["잘 모르겠음"], status="UNKNOWN", source="ANSWER", source_turn=a, quote="잘 모르겠음")
        self.assertEqual([tuple(r) for r in self.s.con.execute("SELECT rev, status FROM facts_current WHERE fact_id='F-C2'")], [(2, "UNKNOWN")])


class Reject(Fixture):
    def test_C01_no_source(self):
        self.rejects("C01", self.fact, source=None)
        self.rejects("C01", self.fact, source="CHAT")
        self.rejects("C01", self.fact, step=99)
        self.raw("INSERT INTO facts (case_id, flow_no, fact_id, rev, status, source_kind, source_step, recorded_at) VALUES (?,?,?,?,?,?,?,?)",
                 CID, 1, "F-T1", 1, "CONFIRMED", None, self.a1, T)

    def test_C02_no_quote(self):
        self.rejects("C02", self.fact, quote=None)
        self.rejects("C02", self.fact, quote="  ")
        self.rejects("C02", self.ledger_fact, quote=None)

    def test_C03_quote_not_in_source(self):
        self.rejects("C03", self.fact, quote="해외 서버에 둡니다")
        self.rejects("C03", self.fact, quote="국내 발송 업체에 맡기려고 … 계약서에 적었습니다")
        self.rejects("C03", self.ledger_fact, quote="제휴사 쿠폰 발송")

    def test_C04_no_answer_turn(self):
        self.rejects("C04", self.fact, source="ANSWER", source_turn=5, quote="예")
        self.rejects("C04", self.fact, source_turn=1)
        self.rejects("C04", self.s.add_turn, CID, "USER", "ANSWER", "예", ["예"], self.a1, T, reply_to=7)

    def test_C05_ledger_source_rules(self):
        self.rejects("C05", self.fact, "F-T3", source="LEDGER", status="INFERRED", ledger_version="20261028",
                     ledger_rows=["첨부4-0025"], quote="고객센터")            # 원장 조회 '—' 사실
        self.rejects("C05", self.ledger_fact, step=self.a1)                   # A3가 아닌 단계
        self.rejects("C05", self.ledger_fact, rows=[])                        # 일치인데 행 없음
        self.rejects("C05", self.ledger_fact, values=("없음",), status="INFERRED", quote=None)   # 일치 없음인데 행 있음
        self.rejects("C05", self.fact, ledger_rows=["첨부1-0040"])            # 문의 출처에 행

    def test_C06_ledger_row_or_version(self):
        self.rejects("C06", self.ledger_fact, rows=["첨부1-9999"])
        self.rejects("C06", self.ledger_fact, rows=["첨부4-0025"], quote="고객센터")   # L1은 첨부1·2만
        self.rejects("C06", self.ledger_fact, ledger_version="19990101")

    def test_C07_unknown_fact_id(self):
        self.rejects("C07", self.fact, "F-ZZ")
        self.rejects("C07", self.s.add_turn, CID, "AGENT", "QUESTION", "?", ["예"], self.a1, T, flow_no=1, fact_id="F-ZZ", ask_no=1)
        self.raw("INSERT INTO facts (case_id, flow_no, fact_id, rev, status, source_kind, source_step, recorded_at) VALUES (?,?,?,?,?,?,?,?)",
                 CID, 1, "F-ZZ", 1, "CONFIRMED", "INQUIRY", self.a1, T)

    def test_C08_value_outside_allowed(self):
        self.rejects("C08", self.fact, values=["아마도"])
        self.rejects("C08", self.fact, values=["예", "아니오(우리 회사 안에서만)"])   # 단일 사실
        self.rejects("C08", self.fact, "F-C1", ["예(나라)"], quote="국내 발송 업체")    # 나라 누락
        self.rejects("C08", self.fact, values=[["예", "덧붙인 글"]])
        q = self.s.add_turn(CID, "AGENT", "QUESTION", "?", ["예", "아니오(우리 회사 안에서만)", "잘 모르겠음"], self.a1, T, flow_no=1, fact_id="F-T1", ask_no=1)
        self.rejects("C08", self.s.add_turn, CID, "USER", "ANSWER", "아마", ["아마"], self.a1, T, reply_to=q)
        self.rejects("C08", self.s.add_turn, CID, "AGENT", "QUESTION", "?", ["아마"], self.a1, T, flow_no=1, fact_id="F-T1", ask_no=1)
        fr = self.fact()
        self.raw("INSERT INTO fact_values (fact_row_id, fact_id, ord, label) VALUES (?,?,?,?)", fr, "F-T1", 2, "아마도")

    def test_C09_status_rules(self):
        self.rejects("C09", self.fact, status="NOT_FOUND")                    # 모름인데 값
        self.rejects("C09", self.fact, values=[], status="CONFIRMED")        # 값 없는 확인
        self.rejects("C09", self.fact, values=["잘 모르겠음"], status="CONFIRMED", quote=None)
        self.rejects("C09", self.fact, values=["잘 모르겠음"], status="UNKNOWN", quote=None)  # 문의에서 에이전트가 미확인을 고름
        self.rejects("C09", self.ledger_fact, status="CONFIRMED")
        self.rejects("C09", self.ledger_fact, status="INFERRED")                       # 명시 일치는 MATCHED
        self.rejects("C09", self.ledger_fact, values=("포괄 일치",), status="MATCHED")   # 포괄 일치는 INFERRED
        self.rejects("C09", self.ledger_fact, values=("없음",), rows=[], status="MATCHED", quote=None)
        self.rejects("C09", self.fact, status="MATCHED")                              # 원장 밖 출처의 MATCHED
        self.rejects("C09", self.fact, status="MAYBE")

    def test_C10_flow_rules(self):
        self.rejects("C10", self.fact, "F-S1", [["서비스명(자유 입력)", "회원"]], quote="회원", flow=1)
        self.rejects("C10", self.fact, flow=None)
        self.rejects("C10", self.fact, flow=5)

    def test_C11_no_package_version(self):
        self.rejects("C11", self.judgment, package_version=None)
        self.rejects("C11", self.judgment, package_version="v9")
        a8 = self.a8
        self.raw("""INSERT INTO case_judgments (case_id, step_seq, reference_date, rule_set_version, package_version, fact_map_sha256,
                    model_name, temperature, prompt_versions, exit_type, exit_reasons, conclusive, flags, premise_note, created_at)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                 CID, a8, "2026-12-01", "v1.0", None, "x" * 64, "m", 0.0, "[]", "ANSWER", "[]", 1, "{}", "전제", T)

    def test_C12_versions(self):
        self.rejects("C12", self.judgment, rule_set_version="v9")
        self.rejects("C12", self.judgment, fact_map_sha256="0" * 64)
        self.rejects("C12", self.judgment, ledger_version="19990101")
        self.ledger_fact()
        self.rejects("C12", self.judgment)                                    # 원장 사실을 썼는데 판 없음
        self.rejects("C12", self.judgment, ledger_version="20261027")

    def test_C13_missing_citation_key(self):
        good = dict(issue=0, kind="LAW_UNIT", key="개인정보보호법|26|1||", text_hash=H26, origin="PACKAGE")
        for bad in (dict(good, key="개인정보보호법|99|1||"), dict(good, text_hash=H15),
                    dict(good, kind="GUIDE_SECTION", key="G13-92e53a760a", text_hash=H26),
                    dict(good, kind="ANNEX", key="개인정보보호법|별표|99"),
                    dict(good, kind="LEDGER_ROW", key="첨부1-0040", text_hash=H26, ledger_version="20261028"),   # 해시 다름
                    dict(good, kind="LEDGER_ROW", key="첨부1-9999", text_hash=H26, ledger_version="20261028"),
                    dict(good, issue=3)):
            self.rejects("C13", self.judgment, citations=[bad], ledger_version=bad.get("ledger_version"))

    def test_C14_slot_rules(self):
        def with_slot(k, **ch):
            slots = []
            for kk in [r[0] for r in self.s.con.execute("SELECT slot_key FROM slot_catalog ORDER BY sort_order")]:
                v = self.s.one("SELECT value FROM slot_options WHERE slot_key=? ORDER BY ord", kk)[0]
                s = dict(flow=1, slot=kk, status="CONFIRMED", values=[v], basis_facts=[], unknown_facts=[])
                if kk == k:
                    s.update(ch)
                slots.append(s)
            return slots
        self.rejects("C14", self.judgment, slots=with_slot("legal_basis", values=["모름"]))
        self.rejects("C14", self.judgment, slots=with_slot("legal_basis", values=["계약 이행", "법령"]))
        self.rejects("C14", self.judgment, slots=with_slot("legal_basis", values=[]))
        self.rejects("C14", self.judgment, slots=with_slot("legal_basis", status="PARTIAL", unknown_facts=["F-B1"]))  # 단일 슬롯
        self.rejects("C14", self.judgment, slots=with_slot("legal_basis")[:7])
        self.rejects("C14", self.judgment, slots=with_slot("legal_basis", slot="purpose"))
        jid = self.judgment()
        self.raw("INSERT INTO case_judgment_slot_values (judgment_id, flow_no, slot_key, ord, value) VALUES (?,?,?,?,?)",
                 jid, 1, "legal_basis", 2, "모름")

    def test_C15_issue_rules(self):
        ok = dict(flow=1, issue="처리위탁", package_id="PKG04", grade="M", rule_ids=["R-P03"], conditions=["C1"],
                  interp_flags=[], conclusive=True, visible=False)
        self.rejects("C15", self.judgment, issues=[dict(ok, issue="신용정보")])
        self.rejects("C15", self.judgment, issues=[dict(ok, conditions=["C9"])])
        self.rejects("C15", self.judgment, issues=[dict(ok, interp_flags=["I09"])])            # v2에서 '사실로 풂'
        self.rejects("C15", self.judgment, issues=[dict(ok, rule_ids=["R-X99"])])
        self.rejects("C15", self.judgment, case_grade="H")
        jid = self.judgment(issues=[dict(ok, issue="수집·이용 근거", package_id="PKG01", conditions=["C5"], interp_flags=["I04"])],
                            citations=[])
        self.assertEqual(jid, 1)

    def test_C16_judgment_links(self):
        self.rejects("C16", self.judgment, step_seq=self.a3)
        self.rejects("C16", self.judgment, judged_turn=9)
        self.s.add_flow(CID, 2, "흐름2", "회사", "카페", self.a1)
        fr2 = self.fact(flow=2)
        slots = self.s.con.execute("SELECT slot_key FROM slot_catalog ORDER BY sort_order").fetchall()

        def mk(**ch):
            out = []
            for f in (1, 2):
                for (k,) in slots:
                    v = self.s.one("SELECT value FROM slot_options WHERE slot_key=? ORDER BY ord", k)[0]
                    s = dict(flow=f, slot=k, status="CONFIRMED", values=[v], basis_facts=[], unknown_facts=[])
                    if f == 1 and k == "transfers":
                        s.update(ch)
                    out.append(s)
            return out
        self.rejects("C16", self.judgment, slots=mk(basis_facts=[fr2]))                     # 흐름 2 사실을 흐름 1 근거로
        self.rejects("C16", self.judgment, slots=mk(status="PARTIAL", unknown_facts=["F-T4"]))  # 미확인 아닌 사실

    def test_C11_C12_case_pins(self):
        """사안 시작 때 고정하는 판 셋(06 2.4-3): 없는 판은 시작부터 거부, 판정의 판은 고정값과 같아야 한다."""
        new = "00000000-0000-4000-8000-0000000000bb"
        n0 = self.count("cases")
        for code, kw in (("C11", dict(package_version="v9")), ("C11", dict(package_version="")),
                         ("C12", dict(rule_set_version="v9")), ("C12", dict(ledger_version="19990101"))):
            args = dict(requester_id=REQ, rule_set_version="v1.0", package_version="v2", ledger_version="20261028")
            args.update(kw)
            with self.assertRaises(st.Rejected) as cm:
                self.s.add_case(new, CONTENT, T, **args)
            self.assertEqual(cm.exception.code, code)
        with self.assertRaises(st.Rejected) as cm:
            self.s.add_case(new, CONTENT, T, " ", "v1.0", "v2")
        self.assertEqual(cm.exception.code, "C01")
        self.assertEqual(self.count("cases"), n0)
        self.s.add_case(new, CONTENT, T, REQ, "v1.0", "v1")          # 꾸러미 v1로 시작한 사안(원장 없이)
        self.s.add_step(new, "A8", "CODE", "기록", {}, {}, T)
        base = dict(step_seq=1, reference_date="2026-12-01", rule_set_version="v1.0", fact_map_sha256=sha(st.FACT_MAP),
                    model_name="m", temperature=0.0, prompt_versions=[], case_grade=None, exit_type="ANSWER", exit_reasons=[],
                    conclusive=True, flags={}, created_at=T)
        with self.assertRaises(st.Rejected) as cm:
            self.s.add_judgment(new, package_version="v2", **base)    # 사안은 v1로 고정
        self.assertEqual((cm.exception.code, self.count("case_judgments")), ("C12", 0))
        with self.assertRaises(st.Rejected) as cm:
            self.s.add_judgment(new, package_version="v1", ledger_version="20261028", **base)   # 원장 없이 시작한 사안
        self.assertEqual(cm.exception.code, "C12")
        self.assertEqual(self.s.add_judgment(new, package_version="v1", **base), 1)

    def test_C17_prompt_version_format(self):
        for bad in ("1", "예시-0", "1@abc", "1@0A1B2C3D", "1@0a1b2c3d4", "@0a1b2c3d", "1 2@0a1b2c3d"):
            self.rejects("C17", self.s.add_step, CID, "A5", "LLM", "답변", {}, {}, T, prompt_id="p", prompt_version=bad,
                         model_name="m", temperature=0.0)
        self.s.add_step(CID, "A5", "LLM", "답변", {}, {}, T, prompt_id="p", prompt_version="12@0a1b2c3d", model_name="m", temperature=0.0)

    def test_attempts_up_to_three(self):
        """검증 실패·일시 오류를 각각 1회 재시도하면 한 호출이 최대 3번(06 F3)."""
        for n in (2, 3):
            seq = self.s.add_step(CID, "A5", "LLM", f"답변{n}", {}, {}, T, prompt_id="p", prompt_version=PV, model_name="m",
                                  temperature=0.0, status="RETRY_OK", attempts=n)
            self.assertEqual(self.s.one("SELECT attempts FROM steps WHERE seq=?", seq)[0], n)
        self.rejects("DB", self.s.add_step, CID, "A5", "LLM", "답변4", {}, {}, T, prompt_id="p", prompt_version=PV,
                     model_name="m", temperature=0.0, status="RETRY_OK", attempts=4)
        self.rejects("DB", self.s.add_step, CID, "A5", "LLM", "답변0", {}, {}, T, prompt_id="p", prompt_version=PV,
                     model_name="m", temperature=0.0, attempts=0)

    def test_C17_llm_record(self):
        self.rejects("C17", self.s.add_step, CID, "A5", "LLM", "답변", {}, {}, T)
        self.rejects("C17", self.s.add_step, CID, "A5", "LLM", "답변", {}, {}, T, prompt_id="p", prompt_version=PV, model_name="m")
        self.raw("INSERT INTO steps (case_id, seq, step, actor, name, input_json, output_json, status, at) VALUES (?,?,?,?,?,?,?,?,?)",
                 CID, 50, "A5", "LLM", "답변", "{}", "{}", "OK", T)
        self.rejects("C17", self.judgment, prompt_versions=[{"prompt_id": "p", "version": "2@0a1b2c3d"}])
        self.rejects("C17", self.judgment, model_name="other")


class Example(unittest.TestCase):
    """예시사안.json 1건: 흐름 2, 원장 조회 1, 모름 1. 사안 DB만으로 A0~A8 재현."""

    @classmethod
    def setUpClass(cls):
        need_dbs()
        cls.tmp = tempfile.TemporaryDirectory()
        cls.before = (sha(st.KB_DB), sha(st.LEDGER_DB))
        cls.a = st.build_example(Path(cls.tmp.name) / "a.db")
        cls.b = st.build_example(Path(cls.tmp.name) / "sub_b.db")
        cls.c = sqlite3.connect(cls.a)

    @classmethod
    def tearDownClass(cls):
        cls.c.close()
        cls.tmp.cleanup()

    def q(self, sql, *a):
        return self.c.execute(sql, a).fetchall()

    def test_rebuild_identical(self):
        self.assertEqual(sha(self.a), sha(self.b))

    def test_knowledge_dbs_untouched(self):
        self.assertEqual((sha(st.KB_DB), sha(st.LEDGER_DB)), self.before)

    def test_example_shape(self):
        self.assertEqual(self.q("SELECT count(*) FROM flows"), [(2,)])
        led = self.q("SELECT f.flow_no, f.fact_id, v.label, r.row_id FROM facts f JOIN fact_values v USING (fact_row_id) "
                     "LEFT JOIN fact_ledger_rows r USING (fact_row_id) WHERE f.source_kind='LEDGER' ORDER BY f.flow_no")
        self.assertEqual(led, [(1, "L1", "명시 일치", "첨부1-0040"), (2, "L1", "없음", None)])
        self.assertEqual(self.q("SELECT flow_no, status FROM facts WHERE fact_id='L1' ORDER BY flow_no"),
                         [(1, "MATCHED"), (2, "INFERRED")])          # 명시 일치 = MATCHED, 일치 없음 = INFERRED
        self.assertEqual(self.q("SELECT requester_id, rule_set_version, package_version, ledger_version FROM cases"),
                         [("예시-요청자", "v1.0", "v2", "20261028")])
        self.assertTrue(all(__import__("re").match(r"^[^@\s]+@[0-9a-f]{8}$", v) for v, in self.q("SELECT prompt_version FROM steps WHERE actor='LLM'")))
        self.assertEqual(len({r[0] for r in self.q("SELECT seq FROM steps WHERE step='A3' AND name='원장 후보 행 열거'")}), 1)
        c2 = self.q("SELECT rev, status, source_kind FROM facts WHERE flow_no=1 AND fact_id='F-C2' ORDER BY rev")
        self.assertEqual(c2, [(1, "NOT_FOUND", "INQUIRY"), (2, "UNKNOWN", "ANSWER"), (3, "UNKNOWN", "ANSWER")])
        self.assertEqual(self.q("SELECT status, unknown_facts FROM case_judgment_slots WHERE flow_no=1 AND slot_key='cross_border'"),
                         [("UNKNOWN", '["F-C2"]')])
        self.assertEqual(self.q("SELECT case_grade, exit_type, package_version, ledger_version FROM case_judgments"),
                         [("H", "TRANSFER", "v2", "20261028")])
        self.assertEqual(self.q("SELECT count(*) FROM case_judgment_slots"), [(16,)])

    def test_every_step_recorded(self):
        got = {r[0] for r in self.q("SELECT DISTINCT step FROM steps")}
        self.assertEqual(got, set(rp.STEPS) - {"A6"})
        gap = [json.loads(r[0])["gap"] for r in self.q("SELECT output_json FROM steps WHERE step='A5'")]
        self.assertEqual(gap, [[]])     # A6는 gap이 있을 때만 → 부르지 않음이 기록으로 설명됨
        for r in self.q("SELECT prompt_id, prompt_version, model_name FROM steps WHERE actor='LLM'"):
            self.assertNotIn(None, r)

    def test_replay_is_committed_table(self):
        """재현표는 사안 DB만 읽어 만든다(지식 DB를 열지 않음). 저장소의 표와 글자까지 같다."""
        text = rp.replay(self.a)
        self.assertEqual(text, P.CASE_REPLAY.read_text(encoding="utf-8"))
        for a in rp.STEPS:
            self.assertIn(f"| {a} | {rp.STEP_NAMES[a]} |", text)

    def test_citations_verify_and_in_force(self):
        s = st.Store(self.a)
        try:
            for kind, key, h, lv in self.q("SELECT cite_kind, cite_key, text_hash, ledger_version FROM case_judgment_citations"):
                self.assertTrue(s.citation_ok(kind, key, h, lv), key)
                if kind == "LAW_UNIT":   # 기준일에 시행 중인 판의 해시(A7 현행 확인과 같은 조회)
                    r = s.one("""SELECT text_hash FROM kb.kb_chunks WHERE unit_key=? AND status IN ('CURRENT','PENDING')
                                 AND effective_from <= '2026-12-01' AND (effective_to IS NULL OR effective_to > '2026-12-01')
                                 ORDER BY effective_from DESC""", key)
                    self.assertEqual(r[0], h, key)
        finally:
            s.close()

    def test_quotes_in_sources(self):
        content = self.q("SELECT content FROM cases")[0][0]
        for q, in self.q("SELECT basis_quote FROM facts WHERE source_kind='INQUIRY' AND basis_quote IS NOT NULL"):
            for piece in q.split(st.QUOTE_SEP):
                self.assertIn(piece, content)


if __name__ == "__main__":
    unittest.main()
