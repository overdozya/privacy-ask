"""슬롯 변환 규칙(06 2.4 slot_rules, 3.2 조건식): 읽기·검사·Python 기준 평가기·테스트 벡터.

- 규칙: data/수집/슬롯변환_v1.json(04 대응표 1.1~1.10을 옮김). 뜻은 그 파일의 '의미' 절이 기준이고, 아래 코드가 그대로 따른다.
- Java(S5 `SlotConverter`)는 이 평가기와 같은 결과를 내야 한다: data/out/case/slot_rules_vectors.json의 벡터를 모두 통과.
- 벡터 = 지은 벡터(data/수집/슬롯변환_v1_벡터.json, 04 표의 행에서 지음. 기대 값은 사람이 적음) + 예시 사안 흐름(case.db의
  지금 사실 → 재현표의 판정 슬롯). 기대 값이 평가기 결과와 다르면 벡터 파일을 쓰지 않고 멈춘다.
- LLM·외부 API를 부르지 않는다. 같은 입력이면 같은 파일.

실행: python3 case/slot_rules.py   (build/에서. 검사 → data/out/case/slot_rules_vectors.json)
"""
import argparse
import hashlib
import json
import re
import sqlite3
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))  # build/: paths.py
import paths as P  # noqa: E402
import store as st  # noqa: E402  (같은 폴더: 기준표 읽기)

RULES_FILE = P.SLOT_RULES
VECTOR_SPEC = P.SLOT_RULES_VECTOR_SPEC
VECTORS_OUT = P.SLOT_RULES_VECTORS

KNOWN = ("CONFIRMED", "MATCHED", "INFERRED")            # 값이 있는 사실 상태
FACT_OPS = ("eq", "in", "contains", "contains_any")
LEDGER_OPS = ("contains", "contains_any")
EFFECTS = {"SINGLE": ("SET", "SKIP"), "MULTI": ("ADD", "SKIP")}
FLAGS = (None, "FACT_CONFLICT", "CHECK_ITEM")
SLOT_STATUSES = ("CONFIRMED", "PARTIAL", "UNKNOWN", "SKIPPED", "OPEN")
WS = re.compile(r"\s+")


def sha256_file(p: Path) -> str:
    return hashlib.sha256(Path(p).read_bytes()).hexdigest()


def load(path: Path = RULES_FILE) -> dict:
    return json.loads(Path(path).read_text(encoding="utf-8"))


def catalog(fact_map: Path = st.FACT_MAP, rule_set: Path = st.RULE_SET) -> dict:
    """기준표(04 tsv + 현업 규칙 세트)를 store.py와 같은 함수로 읽는다."""
    cat, opts = st.read_fact_map(fact_map)
    _, slots, sopts = st.read_rule_set(rule_set)
    facts = {r["fact_id"]: dict(ord=r["ord"], slots=set(r["slot"].split("·")), is_multi=bool(r["is_multi"]), labels=[])
             for r in cat}
    for o in opts:
        facts[o["fact_id"]]["labels"].append(o["label"])
    sl = {s["slot_key"]: dict(value_type=s["value_type"], sort_order=s["sort_order"], values=[]) for s in slots}
    for o in sopts:
        sl[o["slot_key"]]["values"].append(o["value"])
    return {"facts": facts, "slots": sl}


# ───────── 조건식 걷기 ─────────

def atoms(node):
    if "all" in node or "any" in node:
        for c in node.get("all", node.get("any")):
            yield from atoms(c)
    elif "not" in node:
        yield from atoms(node["not"])
    else:
        yield node


def fact_of(atom):
    return atom.get("fact") or atom.get("ledger")


def ref_facts(node):
    return [f for a in atoms(node) if (f := fact_of(a))]


def ref_slots(node):
    return [a["slot"] for a in atoms(node) if "slot" in a]


# ───────── 검사 ─────────

def check(doc: dict, cat: dict, kb_db: Path = P.KB_DB) -> list:
    """규칙 파일이 기준표·DB와 맞는지. 어긋난 것을 글 목록으로 낸다(빈 목록 = 통과)."""
    errs = []
    facts, slots = cat["facts"], cat["slots"]
    if set(doc["slots"]) != set(slots):
        errs.append(f"슬롯 키가 기준표와 다름: {sorted(set(doc['slots']) ^ set(slots))}")

    def chk_atom(where, a):
        if "slot" in a:
            if a["slot"] not in slots:
                errs.append(f"{where}: 없는 슬롯 {a['slot']}")
                return
            if a["op"] not in FACT_OPS:
                errs.append(f"{where}: 슬롯 연산자 {a['op']}")
            for v in a["value"] if isinstance(a["value"], list) else [a["value"]]:
                if v not in slots[a["slot"]]["values"]:
                    errs.append(f"{where}: {a['slot']} 허용 값 밖 {v!r}")
            return
        f = fact_of(a)
        if f not in facts:
            errs.append(f"{where}: 없는 사실 {f}")
            return
        if "ledger" in a:
            if not f.startswith("L") or a["op"] not in LEDGER_OPS or not a.get("column"):
                errs.append(f"{where}: 원장 원자는 L 사실·contains·칸 이름 {a}")
            return
        vals = a["value"] if isinstance(a["value"], list) else [a["value"]]
        if a["op"] == "status":
            bad = [v for v in vals if v not in st.STATUSES]
        elif a["op"] in FACT_OPS:
            bad = [v for v in vals if v not in facts[f]["labels"]]
        else:
            errs.append(f"{where}: 연산자 {a['op']}")
            return
        if bad:
            errs.append(f"{where}: {f} 허용 값 밖 {bad}")
        if a["op"] in ("in", "contains_any") and not isinstance(a["value"], list):
            errs.append(f"{where}: {a['op']}의 값은 목록")

    def chk_node(where, n):
        keys = set(n)
        if keys in ({"all"}, {"any"}):
            for c in n.get("all", n.get("any")):
                chk_node(where, c)
        elif keys == {"not"}:
            chk_node(where, n["not"])
        elif keys in ({"fact", "op", "value"}, {"slot", "op", "value"}, {"ledger", "column", "op", "value"}):
            chk_atom(where, n)
        else:
            errs.append(f"{where}: 모르는 식 {sorted(keys)}")

    keys_needed = set()
    for sk, rules in doc["slots"].items():
        if sk not in slots:
            continue
        vt = slots[sk]["value_type"]
        if [r["ord"] for r in rules] != list(range(1, len(rules) + 1)):
            errs.append(f"{sk}: ord가 1부터 이어지지 않음")
        for r in rules:
            w = f"{sk}#{r['ord']}"
            chk_node(w, r["when"])
            if r["effect"] not in EFFECTS[vt]:
                errs.append(f"{w}: {vt} 슬롯에 {r['effect']}")
            if r["effect"] == "SET" and len(r["values"]) != 1:
                errs.append(f"{w}: SET 값은 하나")
            if r["effect"] == "SKIP" and r["values"]:
                errs.append(f"{w}: SKIP에 값")
            for v in r["values"]:
                if v not in slots[sk]["values"]:
                    errs.append(f"{w}: 허용 값 밖 {v!r}")
            if r.get("flag") not in FLAGS:
                errs.append(f"{w}: 표시 {r.get('flag')!r}")
            if not r.get("src"):
                errs.append(f"{w}: 04 행 표시(src) 없음")
            keys_needed.update(r["basis_keys"])
            if sk in ref_slots(r["when"]):
                errs.append(f"{w}: 자기 슬롯을 봄")
    try:
        eval_order(doc, cat)
    except ValueError as e:
        errs.append(str(e))
    if kb_db and Path(kb_db).exists():
        con = sqlite3.connect(Path(kb_db).resolve().as_uri() + "?mode=ro", uri=True)
        try:
            for k in sorted(keys_needed):
                n = con.execute("SELECT (SELECT COUNT(*) FROM kb_chunks WHERE unit_key=?) + "
                                "(SELECT COUNT(*) FROM guide_sections WHERE section_id=?)", (k, k)).fetchone()[0]
                if not n:
                    errs.append(f"근거 키가 DB에 없음: {k}")
        finally:
            con.close()
    else:
        errs.append(f"KB DB 없음: {kb_db}")
    return errs


def eval_order(doc: dict, cat: dict) -> list:
    """슬롯 원자가 가리키는 슬롯을 먼저. 같은 단계는 sort_order. 고리가 있으면 ValueError."""
    deps = {sk: {s for r in rules for s in ref_slots(r["when"])} for sk, rules in doc["slots"].items()}
    done, out = set(), []
    while len(out) < len(deps):
        ready = sorted((s for s in deps if s not in done and deps[s] <= done), key=lambda s: cat["slots"][s]["sort_order"])
        if not ready:
            raise ValueError(f"슬롯 원자에 고리: {sorted(set(deps) - done)}")
        out.append(ready[0])
        done.add(ready[0])
    return out


# ───────── 평가 ─────────

def _match(op, have, want):
    if op == "eq":
        return len(have) == 1 and have[0] == want
    if op == "in":
        return len(have) == 1 and have[0] in want
    if op == "contains":
        return want in have
    if op == "contains_any":
        return any(w in have for w in want)
    raise ValueError(op)


def _squash(s: str) -> str:
    return WS.sub("", s)


class _Ctx:
    def __init__(self, facts, ledger_rows, slot_results):
        self.facts, self.ledger_rows, self.slots = facts, ledger_rows, slot_results

    def fact(self, f):
        return self.facts.get(f) or {"status": "NOT_FOUND", "values": []}


def ev(node, ctx):
    """→ (값, 모름 사실 집합). 값은 True / False / None(모름). 모름 사실은 값이 None일 때만 채운다."""
    if "all" in node:
        rs = [ev(c, ctx) for c in node["all"]]
        if any(v is False for v, _ in rs):
            return False, frozenset()
        if any(v is None for v, _ in rs):
            return None, frozenset().union(*(u for v, u in rs if v is None))
        return True, frozenset()
    if "any" in node:
        rs = [ev(c, ctx) for c in node["any"]]
        if any(v is True for v, _ in rs):
            return True, frozenset()
        if any(v is None for v, _ in rs):
            return None, frozenset().union(*(u for v, u in rs if v is None))
        return False, frozenset()
    if "not" in node:
        v, u = ev(node["not"], ctx)
        if v is None:
            return None, u
        return (not v), frozenset()
    if "slot" in node:
        r = ctx.slots.get(node["slot"])
        if r is None:
            raise ValueError(f"슬롯 {node['slot']}을 아직 계산하지 않음")
        if r["status"] not in ("CONFIRMED", "PARTIAL"):
            return False, frozenset()
        return _match(node["op"], r["values"], node["value"]), frozenset()
    f = fact_of(node)
    cur = ctx.fact(f)
    if node.get("op") == "status":
        want = node["value"] if isinstance(node["value"], list) else [node["value"]]
        return cur["status"] in want, frozenset()
    if cur["status"] not in KNOWN:
        return None, frozenset([f])
    if "ledger" in node:
        if cur["status"] != "MATCHED":
            return False, frozenset()
        want = node["value"] if isinstance(node["value"], list) else [node["value"]]
        col = _squash(node["column"])
        texts = []
        for rid in cur.get("rows", []):
            if rid not in ctx.ledger_rows:
                raise ValueError(f"원장 행 글이 없음: {rid}")
            texts += [_squash(t) for c, t in ctx.ledger_rows[rid].items() if _squash(c) == col]
        return any(_squash(w) in t for w in want for t in texts), frozenset()
    return _match(node["op"], cur["values"], node["value"]), frozenset()


def support(node, ctx, want: bool) -> set:
    """값이 want(참/거짓)로 정해진 식에서 그 값을 만든 사실. all이 참이면 모든 자식, 거짓이면 거짓인 자식.
    any가 참이면 참인 자식, 거짓이면 모든 자식. not은 안쪽을 반대로. 판이 없는(NOT_FOUND) 사실과 슬롯 원자는 넣지 않는다."""
    if "all" in node or "any" in node:
        kids = node.get("all", node.get("any"))
        every = ("all" in node) == want           # all→참 / any→거짓: 자식 모두가 그 값
        return set().union(*(support(c, ctx, want) for c in kids if every or ev(c, ctx)[0] is want))
    if "not" in node:
        return support(node["not"], ctx, not want)
    f = fact_of(node)
    if f is None or ctx.fact(f)["status"] == "NOT_FOUND":
        return set()
    return {f}


def _order_facts(fs, cat):
    return sorted(set(fs), key=lambda f: cat["facts"][f]["ord"])


def eval_slot(sk, rules, cat, ctx):
    multi = cat["slots"][sk]["value_type"] == "MULTI"
    seen, true_rules, pending = [], [], set()
    decided = None
    for r in rules:
        v, unk = ev(r["when"], ctx)
        seen.append(r)
        if v is None:
            pending |= unk
            continue
        if v is True:
            if r["effect"] == "SKIP" or not multi:
                decided = r
                true_rules.append(r)
                break
            true_rules.append(r)
    unknown = {f for f in pending if ctx.fact(f)["status"] == "UNKNOWN"}
    opened = pending - unknown
    values, flags, fired = [], [], []
    if decided is not None and decided["effect"] == "SKIP" and not unknown:
        status = "SKIPPED"
        fired = [decided["ord"]]
    elif not multi:
        if decided is not None and not unknown:
            status, values, fired = "CONFIRMED", list(decided["values"]), [decided["ord"]]
            flags = [decided["flag"]] if decided.get("flag") else []
        else:
            status = "UNKNOWN" if unknown else "OPEN"
    else:
        adds = [r for r in true_rules if r["effect"] == "ADD"]
        for r in adds:
            fired.append(r["ord"])
            for x in r["values"]:
                if x not in values:
                    values.append(x)
            if r.get("flag") and r["flag"] not in flags:
                flags.append(r["flag"])
        if unknown:
            status = "PARTIAL" if values else "UNKNOWN"
        else:
            status = "CONFIRMED" if values or adds else "OPEN"
    if status in ("UNKNOWN", "OPEN"):
        values, flags, fired = [], [], []
    basis = set()
    for r in seen:
        basis |= {f for f in ref_facts(r["when"]) if ctx.fact(f)["status"] != "NOT_FOUND" and sk in cat["facts"][f]["slots"]}
        if r["ord"] in fired:
            basis |= support(r["when"], ctx, True)
    return {"status": status, "values": values, "basis_facts": _order_facts(basis, cat),
            "unknown_facts": _order_facts(unknown, cat) if status in ("UNKNOWN", "PARTIAL") else [],
            "open_facts": _order_facts(opened, cat), "flags": flags, "fired": fired}


def evaluate(doc: dict, cat: dict, facts: dict, ledger_rows: dict | None = None) -> dict:
    """흐름 하나의 지금 사실(공통 사실 포함) → 슬롯 8개. facts: {사실 ID: {status, values, rows?}}. 없는 사실 = NOT_FOUND."""
    out = {}
    ctx = _Ctx(facts, ledger_rows or {}, out)
    for sk in eval_order(doc, cat):
        out[sk] = eval_slot(sk, doc["slots"][sk], cat, ctx)
    return {sk: out[sk] for sk in sorted(out, key=lambda s: cat["slots"][s]["sort_order"])}


# ───────── DB 행 ─────────

def slot_rule_rows(doc: dict) -> list:
    """slot_rules 테이블 행(06 2.4). note 앞에 04 행 표시를 붙인다."""
    rows = []
    for sk, rules in doc["slots"].items():
        for r in rules:
            note = f"[04 {r['src']}] {r['note']}".rstrip()
            rows.append(dict(version=doc["version"], slot_key=sk, ord=r["ord"], when_json=st.dumps(r["when"]), effect=r["effect"],
                             values_json=st.dumps(r["values"]), flag=r.get("flag"), basis_keys=st.dumps(r["basis_keys"]), note=note))
    return rows


def rule_set_rows(path: Path = st.RULE_SET):
    rs = json.loads(Path(path).read_text(encoding="utf-8"))
    head = dict(version=rs["version"], status=rs["status"], change_reason=rs["change_reason"],
                exit_policy=st.dumps(rs["exit_policy"]), sha256=sha256_file(path))
    risks = [dict(version=rs["version"], rule_id=r["rule_id"], name=r["name"], axis=r["axis"], condition_json=st.dumps(r["condition"]),
                  grade=r["grade"], issue_name=r["issue_name"], legal_refs=st.dumps(r["legal_refs"]), description=r["description"],
                  active=int(bool(r["active"]))) for r in rs["risk_rules"]]
    return head, risks


# ───────── 벡터 ─────────

def _norm_fact(fid, v, cat):
    """지은 벡터의 짧은 꼴 → {status, values, rows?}. 글 = 보기 하나, 목록 = 복수, '?' = 현업 미확인, dict = 그대로."""
    if isinstance(v, dict):
        out = {"status": v["status"], "values": list(v.get("values", []))}
        if v.get("rows"):
            out["rows"] = list(v["rows"])
        return out
    if v == "?":
        return {"status": "UNKNOWN", "values": ["잘 모르겠음"]}
    vals = v if isinstance(v, list) else [v]
    status = "CONFIRMED"
    if fid.startswith("L"):
        status = "MATCHED" if any(st.EXPLICIT_MARK in x for x in vals) else "INFERRED"
    return {"status": status, "values": vals}


def _ledger_texts(row_ids, ledger_db: Path):
    con = sqlite3.connect(Path(ledger_db).resolve().as_uri() + "?mode=ro", uri=True)
    try:
        out = {}
        for rid in sorted(row_ids):
            r = con.execute("SELECT fields_json FROM ledger_rows WHERE row_id=?", (rid,)).fetchone()
            if r is None:
                raise ValueError(f"ledger.db에 없는 행 {rid}")
            out[rid] = {c: t for c, t in json.loads(r[0])}
        return out
    finally:
        con.close()


def example_flows(case_db: Path = P.CASE_DB):
    """예시 사안 case.db → 흐름마다 (지금 사실, 판정 슬롯). 재현표(case/replay.py)와 같은 DB만 읽는다."""
    con = sqlite3.connect(Path(case_db).resolve().as_uri() + "?mode=ro", uri=True)
    try:
        flows = []
        for case_id, flow_no in con.execute("SELECT case_id, flow_no FROM flows ORDER BY case_id, flow_no"):
            facts = {}
            for row_id, fid, status in con.execute(
                    "SELECT fact_row_id, fact_id, status FROM facts_current WHERE case_id=? AND (flow_no=? OR flow_no IS NULL) "
                    "ORDER BY fact_row_id", (case_id, flow_no)):
                vals = [r[0] for r in con.execute("SELECT label FROM fact_values WHERE fact_row_id=? ORDER BY ord", (row_id,))]
                rows = [r[0] for r in con.execute("SELECT row_id FROM fact_ledger_rows WHERE fact_row_id=? ORDER BY ord", (row_id,))]
                facts[fid] = {"status": status, "values": vals if status in KNOWN or status == "UNKNOWN" else []}
                if rows:
                    facts[fid]["rows"] = rows
            jid = con.execute("SELECT MAX(judgment_id) FROM case_judgments WHERE case_id=?", (case_id,)).fetchone()[0]
            want = {}
            for sk, status, basis, unknown in con.execute(
                    "SELECT slot_key, status, basis_facts, unknown_facts FROM case_judgment_slots WHERE judgment_id=? AND flow_no=?",
                    (jid, flow_no)):
                vals = [r[0] for r in con.execute("SELECT value FROM case_judgment_slot_values WHERE judgment_id=? AND flow_no=? "
                                                  "AND slot_key=? ORDER BY ord", (jid, flow_no, sk))]
                bf = [con.execute("SELECT fact_id FROM facts WHERE fact_row_id=?", (i,)).fetchone()[0] for i in json.loads(basis)]
                want[sk] = {"status": status, "values": vals, "basis_facts": bf, "unknown_facts": json.loads(unknown)}
            flows.append((case_id, flow_no, facts, want))
        return flows
    finally:
        con.close()


def _diff(want: dict, got: dict) -> list:
    """기대에 적은 칸만 비교(적지 않은 칸은 보지 않음). 기대에 적지 않은 슬롯은 상태가 OPEN이어야 한다는 뜻이 아니다."""
    out = []
    for sk, w in want.items():
        for k, v in w.items():
            g = got[sk][k]
            if k == "basis_facts" or k in ("unknown_facts", "open_facts"):
                g, v = sorted(g), sorted(v)
            if g != v:
                out.append(f"{sk}.{k}: 기대 {v!r}, 결과 {got[sk][k]!r}")
    return out


def build_vectors(doc: dict, cat: dict, spec_path: Path = VECTOR_SPEC, case_db: Path = P.CASE_DB, ledger_db: Path = P.LEDGER_DB):
    """→ (벡터 문서, 어긋남 목록). 어긋남이 있으면 부르는 쪽이 파일을 쓰지 않는다."""
    spec = json.loads(Path(spec_path).read_text(encoding="utf-8"))
    vectors, bad = [], []
    for v in spec["vectors"]:
        facts = {f: _norm_fact(f, x, cat) for f, x in v["facts"].items()}
        rows = {r for f in facts.values() for r in f.get("rows", [])}
        led = _ledger_texts(rows, ledger_db) if rows else {}
        got = evaluate(doc, cat, facts, led)
        bad += [f"{v['id']} {d}" for d in _diff(v["expect"], got)]
        vectors.append(dict(id=v["id"], covers=v["covers"], why=v["why"], facts=facts, ledger_rows=led, expect=got,
                            checked=sorted(v["expect"])))
    for case_id, flow_no, facts, want in example_flows(case_db):
        rows = {r for f in facts.values() for r in f.get("rows", [])}
        led = _ledger_texts(rows, ledger_db) if rows else {}
        got = evaluate(doc, cat, facts, led)
        # 재현표의 SKIPPED·UNKNOWN 슬롯은 값이 없고, 근거 사실은 판정 행이 적은 사실 ID와 같아야 한다
        bad += [f"예시 흐름 {flow_no} {d}" for d in _diff(want, got)]
        vectors.append(dict(id=f"EX-{flow_no}", covers=["예시사안"], why=f"예시 사안 {case_id} 흐름 {flow_no}: 재현표 판정 슬롯과 같아야 함",
                            facts=facts, ledger_rows=led, expect=got, checked=sorted(want)))
    covers = sorted({c for v in vectors for c in v["covers"]})
    doc_out = {
        "설명": "슬롯 변환 기준 벡터. 입력(흐름 하나의 지금 사실 + 기댄 원장 행 글) → 기대 슬롯 8개. Java SlotConverter는 expect 전체와 같아야 한다",
        "rules": {"file": "수집/" + Path(RULES_FILE).name, "version": doc["version"], "sha256": sha256_file(RULES_FILE)},
        "fact_map_sha256": sha256_file(st.FACT_MAP),
        "rule_set_sha256": sha256_file(st.RULE_SET),
        "slot_order": eval_order(doc, cat),
        "count": len(vectors),
        "covers": covers,
        "vectors": vectors,
    }
    return doc_out, bad


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--rules", default=str(RULES_FILE))
    ap.add_argument("--spec", default=str(VECTOR_SPEC))
    ap.add_argument("--out", default=str(VECTORS_OUT))
    a = ap.parse_args()
    doc, cat = load(a.rules), catalog()
    errs = check(doc, cat)
    if errs:
        sys.exit("규칙 검사 실패:\n" + "\n".join(errs))
    vec, bad = build_vectors(doc, cat, a.spec)
    if bad:
        sys.exit("벡터 기대 값과 평가 결과가 다름:\n" + "\n".join(bad))
    Path(a.out).parent.mkdir(parents=True, exist_ok=True)
    Path(a.out).write_text(json.dumps(vec, ensure_ascii=False, indent=1, sort_keys=True) + "\n", encoding="utf-8", newline="\n")
    n = sum(len(r) for r in doc["slots"].values())
    print(f"규칙 {n}개 검사 통과, 벡터 {vec['count']}개 → {a.out}")


if __name__ == "__main__":
    main()
