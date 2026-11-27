"""슬롯 변환 규칙(case/slot_rules.py) 테스트. 실행: python3 -m unittest tests.test_slot_rules -v  (build/에서)

- 해석기: 테스트 안에서 지은 작은 규칙으로 3값 논리·원자·단일/복수 슬롯 상태·의존 순서를 고정한다(데이터 없이 돈다)
- 규칙 파일: 사실 ID·라벨·슬롯 값이 기준표에 있고, 근거 키가 KB DB에 있다(06 7절 S3 ②)
- 벡터: 04 1.1~1.10 행마다 하나 이상, 결정 2·3·5·6·7 포함(③), 예시 사안 흐름 1·2가 재현표와 같다(④), 파일이 다시 만들어도 같다
data/수집/슬롯변환_v1.json·privacyask.db·ledger.db·case.db가 없으면 그 테스트는 건너뛴다.
"""
import copy
import json
import re
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent  # build/
sys.path.insert(0, str(ROOT))
import paths as P  # noqa: E402
sys.path.insert(0, str(ROOT / "case"))
import slot_rules as sr  # noqa: E402

FOUR = P.REPO / "docs" / "04_쟁점사실_대응표_20261126.md"


def need(*paths):
    for p in paths:
        if not Path(p).exists():
            raise unittest.SkipTest(f"{p} 없음")


# ───────── 해석기(작은 규칙) ─────────

CAT = {
    "facts": {
        "A": dict(ord=1, slots={"s1"}, is_multi=False, labels=["예", "아니오", "잘 모르겠음"]),
        "B": dict(ord=2, slots={"s1"}, is_multi=True, labels=["x", "y", "z", "잘 모르겠음"]),
        "C": dict(ord=3, slots={"s2"}, is_multi=False, labels=["예", "아니오", "잘 모르겠음"]),
        "L9": dict(ord=4, slots={"s1"}, is_multi=False, labels=["명시 일치", "없음"]),
    },
    "slots": {
        "s1": dict(value_type="SINGLE", sort_order=10, values=["가", "나", "다"]),
        "m1": dict(value_type="MULTI", sort_order=20, values=["p", "q", "r"]),
        "s2": dict(value_type="SINGLE", sort_order=5, values=["예", "아니오"]),
    },
}
K = lambda *v: {"status": "CONFIRMED", "values": list(v)}
U = {"status": "UNKNOWN", "values": ["잘 모르겠음"]}


def rule(ord, when, effect, values, flag=None):
    return dict(ord=ord, src="t", when=when, effect=effect, values=values, flag=flag, basis_keys=[], note="")


def ev(node, facts, slots=None):
    return sr.ev(node, sr._Ctx(facts, {"r1": {"법적 근거": "제15조 제1항\n제1호(동의)"}}, slots or {}))[0]


class Logic(unittest.TestCase):
    def test_kleene(self):
        t, f, n = {"fact": "A", "op": "eq", "value": "예"}, {"fact": "A", "op": "eq", "value": "아니오"}, {"fact": "C", "op": "eq", "value": "예"}
        facts = {"A": K("예")}                         # C는 NOT_FOUND → 모름
        cases = [({"all": [t, n]}, None), ({"all": [f, n]}, False), ({"any": [t, n]}, True), ({"any": [f, n]}, None),
                 ({"not": n}, None), ({"not": f}, True), ({"all": []}, True), ({"any": []}, False)]
        for node, want in cases:
            self.assertIs(ev(node, facts), want, node)

    def test_unknown_facts_only_from_deciding_children(self):
        n1, n2 = {"fact": "B", "op": "contains", "value": "x"}, {"fact": "C", "op": "eq", "value": "예"}
        f = {"fact": "A", "op": "eq", "value": "아니오"}
        ctx = sr._Ctx({"A": K("예"), "B": U}, {}, {})
        v, unk = sr.ev({"any": [{"all": [f, n1]}, n2]}, ctx)      # 첫 갈래는 거짓이라 B는 모름 사실이 아님
        self.assertIsNone(v)
        self.assertEqual(unk, {"C"})

    def test_ops_and_multi(self):
        facts = {"B": K("x", "y")}
        self.assertIs(ev({"fact": "B", "op": "eq", "value": "x"}, facts), False)          # eq = 정확히 하나
        self.assertIs(ev({"fact": "B", "op": "contains", "value": "y"}, facts), True)
        self.assertIs(ev({"fact": "B", "op": "contains_any", "value": ["z", "x"]}, facts), True)
        self.assertIs(ev({"fact": "B", "op": "in", "value": ["x", "y"]}, facts), False)   # in = 정확히 하나가 목록 안
        self.assertIs(ev({"fact": "A", "op": "in", "value": ["예"]}, {"A": K("예")}), True)

    def test_status_atom_is_two_valued(self):
        self.assertIs(ev({"fact": "C", "op": "status", "value": "NOT_FOUND"}, {}), True)
        self.assertIs(ev({"fact": "C", "op": "status", "value": ["CONFIRMED", "UNKNOWN"]}, {"C": U}), True)
        self.assertIs(ev({"not": {"fact": "C", "op": "status", "value": "NOT_FOUND"}}, {"C": U}), True)

    def test_slot_atom_false_unless_known(self):
        a = {"slot": "s2", "op": "eq", "value": "예"}
        for st_ in ("UNKNOWN", "OPEN", "SKIPPED"):
            self.assertIs(ev(a, {}, {"s2": {"status": st_, "values": []}}), False)
        self.assertIs(ev(a, {}, {"s2": {"status": "CONFIRMED", "values": ["예"]}}), True)
        self.assertIs(ev({"slot": "m1", "op": "contains", "value": "p"}, {}, {"m1": {"status": "PARTIAL", "values": ["p"]}}), True)

    def test_ledger_atom(self):
        a = {"ledger": "L9", "column": "법적  근거", "op": "contains", "value": "제1항제1호(동의)"}   # 공백은 빼고 비교
        self.assertIs(ev(a, {"L9": {"status": "MATCHED", "values": ["명시 일치"], "rows": ["r1"]}}), True)
        self.assertIs(ev(a, {"L9": {"status": "INFERRED", "values": ["없음"]}}), False)
        self.assertIsNone(ev(a, {}))
        with self.assertRaises(ValueError):
            ev(a, {"L9": {"status": "MATCHED", "values": ["명시 일치"], "rows": ["r2"]}})


class Slots(unittest.TestCase):
    DOC = {"version": "t", "slots": {
        "s2": [rule(1, {"fact": "C", "op": "eq", "value": "예"}, "SET", ["예"]),
               rule(2, {"fact": "C", "op": "eq", "value": "아니오"}, "SET", ["아니오"])],
        "s1": [rule(1, {"not": {"slot": "s2", "op": "eq", "value": "예"}}, "SKIP", []),
               rule(2, {"fact": "A", "op": "eq", "value": "예"}, "SET", ["가"], flag="CHECK_ITEM"),
               rule(3, {"fact": "B", "op": "contains", "value": "x"}, "SET", ["나"])],
        "m1": [rule(1, {"fact": "B", "op": "contains", "value": "x"}, "ADD", ["p", "q"]),
               rule(2, {"fact": "B", "op": "contains", "value": "y"}, "ADD", ["q", "r"], flag="FACT_CONFLICT"),
               rule(3, {"fact": "A", "op": "eq", "value": "예"}, "ADD", ["r"])],
    }}

    def run_(self, facts):
        return sr.evaluate(self.DOC, CAT, facts)

    def test_order_follows_slot_atoms(self):
        self.assertEqual(sr.eval_order(self.DOC, CAT), ["s2", "s1", "m1"])
        bad = copy.deepcopy(self.DOC)
        bad["slots"]["s2"][0]["when"] = {"slot": "s1", "op": "eq", "value": "가"}
        with self.assertRaises(ValueError):
            sr.eval_order(bad, CAT)

    def test_single_first_true(self):
        r = self.run_({"C": K("예"), "A": K("아니오"), "B": K("x")})["s1"]
        self.assertEqual((r["status"], r["values"], r["fired"], r["basis_facts"]), ("CONFIRMED", ["나"], [3], ["A", "B"]))

    def test_single_skip(self):
        r = self.run_({"C": K("아니오"), "A": K("예")})["s1"]
        self.assertEqual((r["status"], r["values"], r["basis_facts"]), ("SKIPPED", [], []))
        r = self.run_({"C": U})["s1"]                                   # 슬롯 원자: 미확인 슬롯은 거짓 → not 참 → SKIP
        self.assertEqual(r["status"], "SKIPPED")

    def test_single_blocked_by_unknown(self):
        """앞 규칙이 미확인 사실로 모름이면 뒤 규칙이 참이어도 UNKNOWN(결정 5: 앞 규칙 값이 더 높은 등급일 수 있음)."""
        r = self.run_({"C": K("예"), "A": U, "B": K("x")})["s1"]
        self.assertEqual((r["status"], r["values"], r["unknown_facts"], r["flags"]), ("UNKNOWN", [], ["A"], []))

    def test_single_open_when_not_found_blocks(self):
        r = self.run_({"C": K("예"), "B": K("x")})["s1"]                # A가 아직 없음 → 뒤 규칙으로 정하고 A는 열림
        self.assertEqual((r["status"], r["values"], r["open_facts"]), ("CONFIRMED", ["나"], ["A"]))
        r = self.run_({"C": K("예")})["s1"]
        self.assertEqual((r["status"], r["values"], r["open_facts"]), ("OPEN", [], ["A", "B"]))

    def test_single_flag(self):
        r = self.run_({"C": K("예"), "A": K("예")})["s1"]
        self.assertEqual((r["values"], r["flags"]), (["가"], ["CHECK_ITEM"]))

    def test_multi_union_and_partial(self):
        r = self.run_({"B": K("x", "y"), "A": K("아니오")})["m1"]
        self.assertEqual((r["status"], r["values"], r["flags"], r["fired"]), ("CONFIRMED", ["p", "q", "r"], ["FACT_CONFLICT"], [1, 2]))
        r = self.run_({"B": K("x"), "A": U})["m1"]                        # 아는 값은 넣고 모르는 값만 미확인(결정 2)
        self.assertEqual((r["status"], r["values"], r["unknown_facts"]), ("PARTIAL", ["p", "q"], ["A"]))
        r = self.run_({"B": U, "A": K("아니오")})["m1"]
        self.assertEqual((r["status"], r["values"], r["unknown_facts"]), ("UNKNOWN", [], ["B"]))
        r = self.run_({})["m1"]
        self.assertEqual((r["status"], r["open_facts"]), ("OPEN", ["A", "B"]))

    def test_check_catches(self):
        cat = copy.deepcopy(CAT)
        cases = [
            (lambda d: d["slots"]["s1"][1]["when"].update(fact="ZZ"), "없는 사실"),
            (lambda d: d["slots"]["s1"][1]["when"].update(value="몰라"), "허용 값 밖"),
            (lambda d: d["slots"]["s1"][1].update(values=["가", "나"]), "SET 값은 하나"),
            (lambda d: d["slots"]["s1"][1].update(values=["없는값"]), "허용 값 밖"),
            (lambda d: d["slots"]["m1"][0].update(effect="SET"), "MULTI 슬롯에 SET"),
            (lambda d: d["slots"]["s1"][1].update(flag="WHATEVER"), "표시"),
            (lambda d: d["slots"]["s1"][1].update(ord=7), "ord"),
            (lambda d: d["slots"]["s2"][0].update(when={"slot": "s1", "op": "eq", "value": "가"}), "고리"),
        ]
        self.assertEqual(sr.check(self.DOC, cat, kb_db=None), [f"KB DB 없음: None"])
        for mutate, word in cases:
            d = copy.deepcopy(self.DOC)
            mutate(d)
            errs = sr.check(d, cat, kb_db=None)
            self.assertTrue(any(word in e for e in errs), (word, errs))


# ───────── 실제 규칙 파일·벡터 ─────────

def four_rows():
    """04 문서 1.1~1.10 표의 행 표시(예 '1.4-5', '1.9-transfers'). 문서가 없으면 None."""
    if not FOUR.exists():
        return None
    out, sec = [], None
    for line in FOUR.read_text(encoding="utf-8").splitlines():
        m = re.match(r"### (1\.\d+) ", line)
        if m:
            sec = m.group(1) if m.group(1) in [f"1.{i}" for i in range(1, 11)] else None
            continue
        if sec and line.startswith("| ") and not line.startswith("| 순서") and not line.startswith("| #") \
                and not line.startswith("| 슬롯") and not line.startswith("|---"):
            first = line.split("|")[1].strip()
            out.append(f"{sec}-{first}" if sec != "1.9" else f"1.9-{first.strip('()').replace(' ', '')}")
    return out


class RuleFile(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        need(P.SLOT_RULES, P.FACT_MAP, P.RULE_SET, P.KB_DB)
        cls.doc, cls.cat = sr.load(), sr.catalog()

    def test_check_passes(self):
        """사실 ID·라벨·슬롯 값이 기준표에 있고 근거 키가 모두 KB DB에 있다(S3 ②)."""
        self.assertEqual(sr.check(self.doc, self.cat), [])

    def test_shape(self):
        self.assertEqual(set(self.doc["slots"]), set(self.cat["slots"]))
        self.assertEqual(sr.eval_order(self.doc, self.cat)[-1], "night_transmission")   # ad_transmission 다음
        n = sum(len(v) for v in self.doc["slots"].values())
        self.assertGreaterEqual(n, 40)
        skips = [(k, r["ord"]) for k, v in self.doc["slots"].items() for r in v if r["effect"] == "SKIP"]
        self.assertEqual(skips, [("night_transmission", 1)])          # 결정 6: cross_border는 질문 조건으로 건너뛰지 않음
        self.assertIn("의미", self.doc)

    def test_every_04_row_has_a_rule_or_reason(self):
        rows = four_rows()
        if rows is None:
            self.skipTest("04 문서 없음")
        # 규칙 없이 다른 곳이 맡는 행: 1.7-5(I26은 꾸러미 v2 조건), 1.9(모름 = 슬롯 상태 규칙), 1.10(보수 변환은 1.4·1.6·1.7 규칙 안)
        elsewhere = {"1.7-5"} | {r for r in rows if r.startswith(("1.9-", "1.10-"))}
        flat = set()
        for src in (r["src"] for v in self.doc["slots"].values() for r in v):
            m = re.match(r"(1\.\d+)-(.+)", src)          # '1.6-3·4·8' → 1.6-3, 1.6-4, 1.6-8
            if m:
                flat |= {f"{m.group(1)}-{part}" for part in m.group(2).split("·")}
        missing = [r for r in rows if r not in flat and r not in elsewhere]
        self.assertEqual(missing, [])


class Vectors(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        need(P.SLOT_RULES, P.SLOT_RULES_VECTOR_SPEC, P.KB_DB, P.LEDGER_DB, P.CASE_DB)
        cls.doc, cls.cat = sr.load(), sr.catalog()
        cls.vec, cls.bad = sr.build_vectors(cls.doc, cls.cat)

    def test_expectations_hold(self):
        self.assertEqual(self.bad, [])

    def test_coverage(self):
        """04 1.1~1.10 행마다 벡터 하나 이상, 결정 2·3·5·6·7 포함(S3 ③)."""
        covers = set(self.vec["covers"])
        for d in ("결정2", "결정3", "결정5", "결정6", "결정7", "예시사안"):
            self.assertIn(d, covers)
        rows = four_rows()
        if rows is None:
            self.skipTest("04 문서 없음")
        self.assertEqual([r for r in rows if r not in covers], [])

    def test_example_flows_match_replay(self):
        """예시 사안 흐름 1·2의 슬롯 8개(상태·값·근거 사실·미확인)가 case.db 판정 행과 같다(S3 ④)."""
        ex = [v for v in self.vec["vectors"] if v["id"].startswith("EX-")]
        self.assertEqual([v["id"] for v in ex], ["EX-1", "EX-2"])
        for v in ex:
            self.assertEqual(len(v["checked"]), 8)
        self.assertEqual(ex[0]["expect"]["cross_border"]["unknown_facts"], ["F-C2"])
        self.assertEqual(ex[1]["expect"]["legal_basis"]["values"], ["동의(신규 필요)"])

    def test_vectors_self_contained(self):
        """Java는 벡터 파일만으로 돈다: 원장 원자가 볼 행 글이 벡터 안에 있다."""
        for v in self.vec["vectors"]:
            rows = {r for f in v["facts"].values() for r in f.get("rows", [])}
            self.assertEqual(set(v["ledger_rows"]), rows, v["id"])
            self.assertEqual(sr.evaluate(self.doc, self.cat, v["facts"], v["ledger_rows"]), v["expect"], v["id"])

    def test_decisions_6_7(self):
        by = {v["id"]: v["expect"] for v in self.vec["vectors"]}
        self.assertEqual((by["C-01"]["transfers"]["values"], by["C-01"]["cross_border"]["values"]), (["없음"], ["있음"]))   # 결정 6
        self.assertEqual(by["N-01"]["night_transmission"]["values"], ["아니오"])                                         # 결정 7

    def test_file_is_rebuild(self):
        need(P.SLOT_RULES_VECTORS)
        with tempfile.TemporaryDirectory() as d:
            out = Path(d) / "v.json"
            out.write_text(json.dumps(self.vec, ensure_ascii=False, indent=1, sort_keys=True) + "\n", encoding="utf-8")
            self.assertEqual(out.read_bytes(), P.SLOT_RULES_VECTORS.read_bytes(), "python3 case/slot_rules.py를 다시 돌릴 것")


if __name__ == "__main__":
    unittest.main()
