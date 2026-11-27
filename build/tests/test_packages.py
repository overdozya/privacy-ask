"""쟁점 꾸러미(D6) 테스트: v1(3단계, 고정)과 v2(대응표 결정 8·9·10)를 같은 검사로 본다. 실행: python3 -m unittest discover -s tests -v  (build/에서)

- 꾸러미 14개 = 현업 issue_catalog 13개(trigger 글자 그대로) + 추가적 이용·제공(제안). 팀에만 있는 4개는 없음
- 현업 trigger의 값 하나하나가 세부 조건(kind=trigger)으로 들어감, 현업 규칙 ID가 seed와 같음
- 대상 키가 모두 DB에 있음(조문·별표·절), 펼친 단위가 실제 청크·별표를 가리킴
- 역할-정책 규칙: 핵심/요건/예외/절차는 ANSWER_BASIS 문서의 use_scope 안만. 밖이면 해석 + 이유
- 출처 추적: 현업 = 규칙 legal_refs의 조, 팀P3 = P3 조항, 위임연결·본문참조 = kb_links에 실제 연결
- 장·문서 통째 넣기 없음, 안내서 '2 법령' 절 없음, 해석 후보 문구가 조문 원문에 있음
- DB = 입력 파일, 잘못된 입력이면 빌드가 멈춤. 재생성 동일은 test_db.RebuildTests가 새 테이블까지 덮음
"""
import hashlib
import json
import re
import shutil
import sqlite3
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent  # build/
sys.path.insert(0, str(ROOT))
import paths as P  # noqa: E402
sys.path.insert(0, str(ROOT / "db"))
import packages as pk  # noqa: E402

DB = P.KB_DB
INPUT, INPUT_V2 = P.PACKAGE_FILES
SEED = P.SEED_1116
P3 = P.BUNDLE / "issue" / "issue_articles.json"
TEAM_ONLY = {"영상정보처리기기", "통신비밀", "신용정보", "임직원 개인정보"}


def law_ref_jo(ref, docs_by_name):
    """'개인정보 보호법 제28조의8' / '정보통신망법 제50조 제1항' → (doc_id, '28-8')"""
    m = re.fullmatch(r"(.+?) 제(\d+)조(?:의(\d+))?(?: .*)?", ref)
    name = {"위치정보법": "위치정보법"}.get(m.group(1), m.group(1))
    return docs_by_name[name], m.group(2) + (f"-{m.group(3)}" if m.group(3) else "")


def key_jo(key):
    name, jo = key.split("|")[:2]
    return name, jo


class PackageChecks:
    """버전마다 같은 검사. 하위 클래스가 VERSION·INPUT·PKG14_STATUS를 정한다."""
    VERSION, INPUT, PKG14_STATUS = None, None, None

    @classmethod
    def setUpClass(cls):
        if not DB.exists():
            raise unittest.SkipTest("data/out/db/privacyask.db 없음. 먼저 python3 db/build_db.py")
        cls.c = sqlite3.connect(DB)
        cls.c.row_factory = sqlite3.Row
        cls.spec = json.loads(cls.INPUT.read_text(encoding="utf-8"))
        cls.pk = {r["package_id"]: r for r in cls.c.execute("SELECT * FROM issue_packages WHERE version=?", (cls.VERSION,))}
        cls.items = cls.c.execute("SELECT * FROM package_items WHERE version=? ORDER BY package_id, ord", (cls.VERSION,)).fetchall()
        cls.catalog = {x["issue_name"]: x for x in json.loads((SEED / "issue_catalog.json").read_text(encoding="utf-8"))}
        cls.rules = json.loads((SEED / "rule_set_v1.0.json").read_text(encoding="utf-8"))["risk_rules"]
        cls.docs = {r["doc_id"]: r for r in cls.c.execute("SELECT * FROM kb_documents")}
        cls.law_docs = {r["law_name"]: d for d, r in cls.docs.items() if r["source_type"] == "LAW"}
        cls.keyname = {}
        for d, k in cls.c.execute("SELECT DISTINCT doc_id, substr(unit_key,1,instr(unit_key,'|')-1) FROM kb_chunks "
                                  "WHERE status='CURRENT' AND doc_id IN (SELECT doc_id FROM kb_documents WHERE source_type='LAW')"):
            cls.keyname[d] = k

    @classmethod
    def tearDownClass(cls):
        cls.c.close()

    def test_14_packages_trigger_verbatim(self):
        names = {r["issue_name"]: r for r in self.pk.values()}
        self.assertEqual(len(self.pk), 14)
        self.assertEqual(set(names) - {"추가적 이용·제공"}, set(self.catalog))
        self.assertFalse(set(names) & TEAM_ONLY)
        for n, r in names.items():
            if n == "추가적 이용·제공":
                self.assertEqual(r["trigger_status"], self.PKG14_STATUS)
                self.assertTrue(r["trigger_note"])
                continue
            self.assertEqual(r["trigger_status"], "현업", n)
            self.assertEqual(json.loads(r["trigger_json"]), self.catalog[n]["trigger"], n)
        self.assertEqual({r["version"] for r in self.pk.values()}, {self.VERSION})

    def test_every_trigger_value_is_a_condition(self):
        for r in self.pk.values():
            if r["trigger_status"] != "현업":
                continue
            conds = [c for c in json.loads(r["conditions_json"]) if c["kind"] == "trigger"]
            whens = [c["when"] for c in conds]
            atoms = json.loads(r["trigger_json"])["all"]
            if len(atoms) > 1:  # 여러 슬롯을 함께 보는 trigger(위치정보 제3자 제공)는 통째로 한 조건
                self.assertIn({"all": atoms}, whens, r["issue_name"])
                continue
            a = atoms[0]
            if a["op"] in ("in", "contains_any"):
                single = "eq" if a["op"] == "in" else "contains"
                for v in a["value"]:
                    self.assertIn({"slot": a["slot"], "op": single, "value": v}, whens, (r["issue_name"], v))
                self.assertEqual(len(conds), len(a["value"]), r["issue_name"])
            else:
                self.assertEqual(whens, [a], r["issue_name"])

    def test_rules_match_seed(self):
        for r in self.pk.values():
            want = sorted(x["rule_id"] for x in self.rules if x["issue_name"] == r["issue_name"])
            self.assertEqual(sorted(json.loads(r["rules_json"])), want, r["issue_name"])

    def test_target_keys_exist(self):
        for it in self.items:
            k, kind = it["target_key"], it["target_kind"]
            if kind == "LAW_UNIT":
                if pk.is_article_prefix(k):
                    n = self.c.execute("SELECT count(*) FROM kb_chunks WHERE status='CURRENT' AND substr(unit_key,1,?)=?",
                                       (len(k), k)).fetchone()[0]
                else:
                    n = self.c.execute("SELECT count(*) FROM kb_chunks WHERE status='CURRENT' AND unit_key=?", (k,)).fetchone()[0]
            elif kind == "ANNEX":
                n = self.c.execute("SELECT count(*) FROM kb_annexes WHERE status='CURRENT' AND annex_key=?", (k,)).fetchone()[0]
            else:
                n = self.c.execute("SELECT count(*) FROM guide_sections WHERE section_id=?", (k,)).fetchone()[0]
            self.assertGreater(n, 0, k)
            units = self.c.execute("SELECT * FROM package_item_units WHERE item_id=?", (it["item_id"],)).fetchall()
            self.assertEqual(len(units), it["unit_count"], k)
            for u in units:
                if u["annex_id"]:
                    a = self.c.execute("SELECT annex_key, status FROM kb_annexes WHERE annex_id=?", (u["annex_id"],)).fetchone()
                    self.assertEqual((a[0], a[1]), (u["unit_key"], "CURRENT"))
                else:
                    ch = self.c.execute("SELECT unit_key, status, doc_id FROM kb_chunks WHERE chunk_id=?", (u["chunk_id"],)).fetchone()
                    self.assertEqual((ch[0], ch[1], ch[2]), (u["unit_key"], "CURRENT", it["target_doc_id"]))

    def test_role_policy_rule(self):
        """역할 핵심/요건/예외/절차 → ANSWER_BASIS 범위 안. 범위 판정은 여기서 따로 다시 계산해 DB 값과 비교한다."""
        for it in self.items:
            d = self.docs[it["target_doc_id"]]
            scope = json.loads(d["use_scope"]) if d["use_scope"] else []
            keys = [u[0] for u in self.c.execute("SELECT unit_key FROM package_item_units WHERE item_id=?", (it["item_id"],))]
            if d["use_policy"] != "ANSWER_BASIS":
                inside = False
            elif it["target_kind"] == "LAW_UNIT":
                inside = all(any(k.startswith(p) for p in scope) for k in keys)
            elif it["target_kind"] == "GUIDE_SECTION":
                sid, anc = it["target_key"], []
                while sid:
                    anc.append(sid)
                    sid = self.c.execute("SELECT parent_id FROM guide_sections WHERE section_id=?", (sid,)).fetchone()[0]
                inside = any(a in scope for a in anc)
            else:  # 별표: 이어진 조문이 범위 안
                linked = [r[0] for r in self.c.execute(
                    "SELECT l.src_key FROM kb_links l JOIN kb_annexes a ON a.annex_id=l.dst_annex_id "
                    "WHERE l.link_type='별표' AND a.annex_key=? AND a.status='CURRENT' AND l.src_chunk_id IS NOT NULL",
                    (it["target_key"],))]
                inside = any(l.startswith(p) for l in linked for p in scope)
            self.assertEqual(bool(it["answer_basis"]), inside, it["target_key"])
            if it["role"] in pk.BASIS_ROLES:
                self.assertEqual(it["answer_basis"], 1, (it["package_id"], it["target_key"], it["role"]))
                self.assertEqual(d["use_policy"], "ANSWER_BASIS")
            if not it["answer_basis"]:
                self.assertEqual(it["role"], "해석", it["target_key"])
                self.assertTrue(it["basis_note"].startswith("즉답 근거 아님"), it["target_key"])
            else:
                self.assertIsNone(it["basis_note"])

    def test_db_equals_input(self):
        got = [(r["package_id"], r["ord"], r["target_kind"], r["target_key"], r["role"], r["source"], r["reason"],
                json.loads(r["conds"]) if r["conds"] else None) for r in self.items]
        want = [(x["package_id"], i, it["kind"], it["key"], it["role"], it["source"], it["why"], it.get("conds"))
                for x in self.spec["packages"] for i, it in enumerate(x["items"], 1)]
        self.assertEqual(got, want)
        sha = hashlib.sha256(self.INPUT.read_bytes()).hexdigest()
        self.assertEqual({r["input_sha256"] for r in self.pk.values()}, {sha})
        files = json.loads(self.c.execute("SELECT v FROM build_info WHERE k='package_files'").fetchone()[0])
        self.assertEqual(files[self.VERSION]["sha256"], sha)
        for x in self.spec["packages"]:
            r = self.pk[x["package_id"]]
            self.assertEqual(json.loads(r["conditions_json"]), x["conditions"])
            self.assertEqual(r["item_count"], len(x["items"]))

    def test_no_whole_chapter_or_law_text_section(self):
        for it in self.items:
            if it["target_kind"] != "GUIDE_SECTION":
                continue
            depth, title = self.c.execute("SELECT depth, title FROM guide_sections WHERE section_id=?",
                                          (it["target_key"],)).fetchone()
            self.assertGreaterEqual(depth, 3, it["target_key"])
            self.assertNotRegex(title, r"^\d+\s*법령$", it["target_key"])

    def test_sources_traceable(self):
        p3 = json.loads(P3.read_text(encoding="utf-8"))
        rules = {x["rule_id"]: x for x in self.rules}
        name_of = {v: k for k, v in self.keyname.items()}
        for x in self.spec["packages"]:
            law_items = [it for it in x["items"] if it["kind"] == "LAW_UNIT"]
            core_jo = {key_jo(it["key"]) for it in law_items if it["role"] == "핵심"}
            pkg_jo = {key_jo(it["key"]) for it in law_items}
            refs = set()
            for rid in x["rules"]:
                for ref in rules[rid]["legal_refs"]:
                    d, jo = law_ref_jo(ref, self.law_docs)
                    refs.add((self.keyname[d], jo))
            p3_keys = set()
            for line in p3:
                if line["issue"] == x["issue"]:
                    for a in line["articles"]:
                        d, jo = law_ref_jo(a, self.law_docs)
                        m = re.search(r"제(\d+)항", a)
                        p3_keys.add((self.keyname[d], jo, m.group(1) if m else ""))
            for it in law_items:
                name, jo = key_jo(it["key"])
                if it["source"] == "현업":
                    self.assertIn((name, jo), refs, (x["package_id"], it["key"]))
                elif it["source"] == "팀P3":
                    hang = it["key"].split("|")[2]
                    self.assertIn((name, jo, hang), p3_keys, (x["package_id"], it["key"]))
                elif it["source"] in ("위임연결", "본문참조"):
                    types = ("위임", "별표", "본문참조") if it["source"] == "위임연결" else ("본문참조", "준용")
                    others = (pkg_jo if it["source"] == "위임연결" else core_jo) - {(name, jo)}
                    ok = False
                    for o_name, o_jo in others:
                        a, b = f"{name}|{jo}|", f"{o_name}|{o_jo}|"
                        n = self.c.execute(
                            "SELECT count(*) FROM kb_links WHERE link_type IN (%s) AND "
                            "((substr(src_key,1,?)=? AND substr(dst_key,1,?)=?) OR (substr(src_key,1,?)=? AND substr(dst_key,1,?)=?))"
                            % ",".join("?" * len(types)), (*types, len(a), a, len(b), b, len(b), b, len(a), a)).fetchone()[0]
                        if n:
                            ok = True
                            break
                    self.assertTrue(ok, (x["package_id"], it["key"], it["source"]))
            for it in x["items"]:
                if it["kind"] == "ANNEX" and it["source"] == "위임연결":
                    n = self.c.execute("SELECT count(*) FROM kb_links l JOIN kb_annexes a ON a.annex_id=l.dst_annex_id "
                                       "WHERE l.link_type='별표' AND a.annex_key=?", (it["key"],)).fetchone()[0]
                    self.assertGreater(n, 0, it["key"])
        self.assertTrue(name_of)

    def test_interp_candidates_quote_real_text(self):
        for x in self.spec["packages"]:
            qs = [q for c in x["conditions"] for q in c.get("interp", [])]
            self.assertTrue(qs, x["package_id"])
            for q in qs:
                k = q["basis"]
                rows = self.c.execute("SELECT text FROM kb_chunks WHERE status='CURRENT' AND (unit_key=? OR "
                                      "(substr(?,-1)='|' AND length(?)-length(replace(?,'|',''))=2 AND substr(unit_key,1,?)=?))",
                                      (k, k, k, k, len(k), k)).fetchall()
                self.assertTrue(rows, k)
                self.assertIn(q["phrase"], "".join(r[0] for r in rows), k)



class V1Tests(PackageChecks, unittest.TestCase):
    VERSION, INPUT, PKG14_STATUS = "v1", INPUT, "제안"

    def test_v1_frozen(self):
        """v1은 고정(사용자, 2026-11-26). 내용을 바꾸고 v1으로 두면 멈추고, v2로 올리면 지나간다."""
        fz = self.spec["frozen"]["v1"]
        self.assertEqual((self.spec["version"], self.spec["status"]), ("v1", "고정"))
        self.assertEqual((fz["decided_by"], fz["decided_at"]), ("사용자", "2026-11-26"))
        self.assertEqual(pk.content_sha256(self.spec["packages"]), fz["packages_sha256"])
        rows = {(r["version"], r["status"], r["decided_by"], r["decided_at"], r["content_sha256"]) for r in self.pk.values()}
        self.assertEqual(rows, {("v1", "고정", "사용자", "2026-11-26", fz["packages_sha256"])})
        files = json.loads(self.c.execute("SELECT v FROM build_info WHERE k='package_files'").fetchone()[0])
        self.assertEqual(files["v1"]["status"], "고정")
        changed = json.loads(INPUT.read_text(encoding="utf-8"))
        changed["packages"][0]["items"][0]["why"] += " (수정)"
        with self.assertRaises(AssertionError) as cm:
            pk.check_frozen(changed)
        self.assertIn("v2", str(cm.exception))
        changed.update(version="v2", status="사용자 검토 전")
        self.assertEqual(pk.check_frozen(changed)[0], "사용자 검토 전")
        changed["status"] = "고정"
        with self.assertRaises(AssertionError):
            pk.check_frozen(changed)  # frozen에 v2 기록 없이 고정이라 할 수 없음

    def test_bad_input_stops_build(self):
        """키가 DB에 없거나, REFERENCE 항목에 요건 역할을 주면 빌드가 멈춘다."""
        with tempfile.TemporaryDirectory() as t:
            db = Path(t) / "x.db"
            shutil.copy(DB, db)
            for mutate, msg in ((lambda s: s["packages"][0]["items"][0].update(key="개인정보보호법|999|1||"), "DB 현행 청크에 없음"),
                                (lambda s: next(it for it in s["packages"][4]["items"] if it["key"] == "G50-2a033acc75")
                                 .update(role="요건"), "즉답 근거 범위 안 항목만")):
                spec = json.loads(INPUT.read_text(encoding="utf-8"))
                spec.update(version="v2", status="사용자 검토 전")  # 고정 검사를 지나 키·역할 검사까지 가게
                mutate(spec)
                f = Path(t) / "in.json"
                f.write_text(json.dumps(spec, ensure_ascii=False), encoding="utf-8")
                con = sqlite3.connect(db)
                con.executescript("DELETE FROM package_item_units; DELETE FROM package_items; DELETE FROM issue_packages;")
                with self.assertRaises(AssertionError) as cm:
                    pk.build_packages(con, f, current=True)
                self.assertIn(msg, str(cm.exception))
                con.close()



def fact_table():
    """대응표 사실 표(.tsv): 사실 ID → 허용 값 목록. 확정본 이름이 있으면 그것, 없으면 초안."""
    for name in ("04_쟁점사실_대응표_20261126.tsv", "04_쟁점사실_대응표_초안_20261126.tsv"):
        f = P.INPUT / name
        if f.exists():
            import csv
            with f.open(encoding="utf-8") as fh:
                return {r["사실ID"]: [v.strip() for v in r["허용 값"].split(" / ")]
                        for r in csv.DictReader(fh, delimiter="\t")}
    raise unittest.SkipTest("대응표 .tsv 없음")


class V2Tests(PackageChecks, unittest.TestCase):
    """v2 = v1 + 대응표 결정 8(PKG14 P-C), 9(첨부10 명시 일치 = 기준선 안), 10(해석 후보 ①20/②3/③9)."""
    VERSION, INPUT, PKG14_STATUS = "v2", INPUT_V2, "사실 조건"

    def conds(self, pid):
        return {c["id"]: c for c in json.loads(self.pk[pid]["conditions_json"])}

    def test_both_versions_in_db_v2_current(self):
        rows = self.c.execute("SELECT version, count(*), sum(is_current) FROM issue_packages GROUP BY version").fetchall()
        self.assertEqual([tuple(r) for r in rows], [("v1", 14, 0), ("v2", 14, 14)])
        self.assertEqual(self.c.execute("SELECT v FROM build_info WHERE k='package_current'").fetchone()[0], "v2")
        self.assertEqual(self.spec["based_on"], "v1")

    def test_interp_status_decision10(self):
        qs = [q for x in self.spec["packages"] for c in x["conditions"] for q in c.get("interp", [])]
        self.assertEqual([q["id"] for q in qs], [f"I{i:02d}" for i in range(1, 33)])
        v1qs = [q for x in json.loads(INPUT.read_text(encoding="utf-8"))["packages"] for c in x["conditions"]
                for q in c.get("interp", [])]
        self.assertEqual([(q["q"], q["basis"], q["phrase"]) for q in qs], [(q["q"], q["basis"], q["phrase"]) for q in v1qs])
        count = {s: [q["id"] for q in qs if q["status"] == s] for s in pk.INTERP_STATUS}
        self.assertEqual({s: len(v) for s, v in count.items()}, {"사실로 풂": 20, "해석 쟁점": 3, "뺌": 9})
        self.assertEqual(count["해석 쟁점"], ["I04", "I26", "I29"])
        self.assertEqual(count["뺌"], ["I06", "I14", "I15", "I18", "I20", "I23", "I24", "I27", "I32"])
        facts = fact_table()
        for q in qs:
            for f in q["facts"]:
                self.assertIn(f, facts, q["id"])
            if q["status"] == "뺌":
                self.assertEqual(q["facts"], [], q["id"])

    def test_fact_conditions_decision8_9(self):
        facts = fact_table()
        for x in self.spec["packages"]:
            for c in x["conditions"]:
                for f in c.get("facts", []):
                    vals = f["value"] if f["op"] == "in" else [f["value"]]
                    for v in vals:
                        self.assertIn(v, facts[f["fact"]], (x["package_id"], c["id"], f["fact"], v))
        c01, c02, c14 = self.conds("PKG01")["C7"], self.conds("PKG02")["C1a"], self.conds("PKG14")
        self.assertEqual((c01["opens"], c02["opens"]), ("PKG14", "PKG14"))
        trig = json.loads(self.pk["PKG14"]["trigger_json"])["any"]
        as_trigger = lambda c: {"all": [c["when"], *c["facts"]]}  # noqa: E731
        self.assertEqual(trig, [as_trigger(c02), as_trigger(c01)])
        self.assertEqual([as_trigger(c14["C1"]), as_trigger(c14["C2"])], trig)
        self.assertEqual(c14["C2"]["interp_off"], ["I29"])  # 결정 9: 첨부10 명시 일치면 해석 표시 끔
        self.assertEqual(json.loads(self.pk["PKG14"]["opened_by"]), ["PKG02.C1a", "PKG01.C7"])
        # 현업 슬롯 값 그대로(결정 8 P-C): 슬롯 원자는 현업 허용 값만
        allowed = {s["slot_key"]: s["allowed_values"] for s in json.loads(
            (SEED / "rule_set_v1.0.json").read_text(encoding="utf-8"))["slot_dictionary"]}
        for a in [*trig[0]["all"], *trig[1]["all"]]:
            if "slot" in a:
                self.assertIn(a["value"], allowed[a["slot"]])

    def test_v2_frozen(self):
        fz = self.spec["frozen"]["v2"]
        self.assertEqual((self.spec["status"], fz["decided_by"], fz["decided_at"]), ("고정", "사용자", "2026-11-26"))
        self.assertEqual(pk.content_sha256(self.spec["packages"]), fz["packages_sha256"])
        rows = {(r["status"], r["decided_by"], r["decided_at"], r["content_sha256"]) for r in self.pk.values()}
        self.assertEqual(rows, {("고정", "사용자", "2026-11-26", fz["packages_sha256"])})
        files = json.loads(self.c.execute("SELECT v FROM build_info WHERE k='package_files'").fetchone()[0])
        self.assertEqual({v: f["status"] for v, f in files.items()}, {"v1": "고정", "v2": "고정"})

    def test_pkg11_fd8_picks_articles(self):
        got = {it["target_key"]: json.loads(it["conds"]) if it["conds"] else None
               for it in self.items if it["package_id"] == "PKG11"}
        for k in ("위치정보법|18|1||", "위치정보법|18|2||", "위치정보법|18|3||", "위치정보법시행령|22|"):
            self.assertEqual(got[k], ["C1a"], k)
        for k in ("위치정보법|19|1||", "위치정보법|19|5||", "위치정보법시행령|23|"):
            self.assertEqual(got[k], ["C1b"], k)
        c = self.conds("PKG11")
        self.assertEqual(c["C1a"]["facts"][0]["fact"], "F-D8")

    def test_v2_items_same_as_v1_except_pkg11_conds(self):
        """v1 항목은 그대로(PKG11 조건 칸만 바뀜), 더한 것은 대응표 근거 키 4개(해석, 즉답 근거 안, 꾸러미 끝)."""
        v1 = {(r["package_id"], r["target_kind"], r["target_key"]): r for r in self.c.execute(
            "SELECT * FROM package_items WHERE version='v1'")}
        v2 = {(r["package_id"], r["target_kind"], r["target_key"]): r for r in self.items}
        added = set(v2) - set(v1)
        self.assertEqual(set(v1) - set(v2), set())
        self.assertEqual({k[2] for k in added},
                         {"위치정보법|2||1|", "개인정보보호법|2||1|", "정보통신망법|2|1|13|", "G14-9457332c40"})
        for k in added:
            r = v2[k]
            self.assertEqual((r["role"], r["source"], r["answer_basis"]), ("해석", "대응표", 1), k)
            self.assertEqual(r["ord"], max(x["ord"] for x in self.items if x["package_id"] == k[0]), k)
        for k, a in v1.items():
            b = v2[k]
            for col in ("ord", "role", "source", "reason", "answer_basis", "char_count"):
                self.assertEqual(a[col], b[col], (k, col))
            if a["conds"] != b["conds"]:
                self.assertEqual(k[0], "PKG11", k)


if __name__ == "__main__":
    unittest.main()
