"""사안 기록(D9) 저장 함수. 에이전트(A0~A8)는 이 함수로만 쓴다. 운영(Java)은 같은 검사를 옮겨 구현한다.

- 기준표: 사실 ID·허용 값은 04 대응표 tsv에서, 슬롯 값은 현업 rule_set_v1.0.json에서 읽어 DB에 넣는다(손으로 옮겨 적지 않음).
- 거부 규칙 C01~C17: 칸 제약(schema.sql)으로 되는 것은 DB가 한 번 더 막고, 다른 DB(privacyask.db·ledger.db)를
  봐야 하는 것은 여기서 ATTACH(읽기 전용)로 확인한다. 거부하면 Rejected(code)를 던지고 아무것도 남기지 않는다.
- 시각은 부르는 쪽이 넘긴다(now()를 쓰지 않음): 같은 입력이면 같은 DB.

실행: python3 case/store.py   (build/에서. data/수집/예시사안.json → data/out/case/case.db)
"""
import argparse
import csv
import hashlib
import json
import re
import sqlite3
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))  # build/: paths.py
import paths as P  # noqa: E402
FACT_MAP = P.FACT_MAP
RULE_SET = P.RULE_SET
KB_DB = P.KB_DB
LEDGER_DB = P.LEDGER_DB
SCHEMA = HERE / "schema.sql"
EXAMPLE = P.EXAMPLE_CASE
OUT = P.CASE_DB
SLOT_RULES = P.SLOT_RULES

# 허용 값 칸 읽는 법(무엇을 보고 정하나). 값 자체는 tsv 글자 그대로다.
UNKNOWN_LABELS = ("잘 모르겠음", "모름")               # 현업이 고르는 '모름' 버튼
DETAIL_MARKERS = ("(자유 입력", "(법령·조문)", "(나라)", "(이익 적기)")  # 괄호 안이 '적을 것'이면 자유 입력이 따라옴
DETAIL_LABELS = ("날짜",)
MULTI_MARK = "(복수)"
EXPAND_PREFIX = "해당 "                                # '해당 항목(복수)': 보기는 질문 문장 '? ' 뒤의 쉼표 목록
QUOTE_SEP = " … "                                     # 근거 문구 여러 조각을 잇는 표시
ATTACH_RE = re.compile(r"첨부(\d+(?:·\d+)*)")
LREF_RE = re.compile(r"(?<![A-Za-z0-9])L([1-4])(?![0-9])")
PREMISE_NOTE = "제시된 사실관계를 전제로 한 의견"
EXPLICIT_MARK = "명시"                                # L 사실 보기 '명시 일치'(원장 명시 일치 → MATCHED). '포괄 일치'는 INFERRED
PROMPT_VERSION_RE = re.compile(r"^[^@\s]+@[0-9a-f]{8}$")   # '<버전>@<보낸 글 sha256 앞 8자>' (06 2.4-5)
STATUSES = ("CONFIRMED", "MATCHED", "INFERRED", "NOT_FOUND", "UNKNOWN")

RULES = {
    "C01": "출처 없음: 출처 종류가 없거나 그 사안의 단계 기록이 없음",
    "C02": "근거 문구 없음: 값이 있는 사실에 근거 문구가 없음",
    "C03": "근거 문구가 출처에 없음: 문구 조각이 문의 원문·답변 턴·원장 행 글 안에 없음",
    "C04": "없는 답변 턴: ANSWER 출처가 그 사안의 현업 답 턴을 가리키지 않음",
    "C05": "원장 출처 조건 위반: 원장 조회가 없는 사실, A3가 아닌 단계, 일치 값인데 행 없음, 원장 밖 출처에 행",
    "C06": "없는 원장 행·판: ledger.db에 없는 행 ID·처리방침 판, 판이 다른 행, 그 사실이 볼 수 없는 첨부의 행",
    "C07": "없는 사실 ID: 04 대응표에 없는 사실(사실 기록·질문 턴)",
    "C08": "허용 값 밖 사실: 보기에 없는 값, 단일 사실에 값 여럿, 자유 입력 누락·잘못 붙음, 답 턴의 보기 밖 선택",
    "C09": "확인 상태 위반: 모름인데 값 있음, '잘 모르겠음'인데 현업 미확인 아님(또는 그 반대), 원장 출처인데 명시 일치는 MATCHED·그 밖은 INFERRED가 아님, 원장 밖 출처의 MATCHED",
    "C10": "흐름 위반: 공통 사실에 흐름, 흐름 사실에 흐름 없음, 없는 흐름",
    "C11": "꾸러미 버전 없음: 사안·판정에 꾸러미 판이 없거나 privacyask.db에 없는 판",
    "C12": "기준 판 불일치: 규칙 세트·대응표 해시가 기준표와 다름, 처리방침 판이 없거나 원장 사실과 다름, 판정의 판이 사안 시작 때 고정한 값과 다름",
    "C13": "없는 인용 키: 조문·별표·절·원장 행 키가 없거나 text_hash가 그 키의 어느 판과도 맞지 않음",
    "C14": "슬롯 위반: 허용 값 밖, 단일 슬롯 값 여럿, 상태와 값이 안 맞음, 흐름마다 8개가 아님",
    "C15": "쟁점 위반: 그 꾸러미 판에 없는 쟁점·조건·해석 쟁점, 없는 규칙 ID, 사안 등급이 쟁점 최고 등급이 아님",
    "C16": "판정 연결 위반: A8 단계가 아님, 다른 사안·흐름의 사실을 근거로 씀, 현업 미확인 아닌 사실을 미확인으로 적음",
    "C17": "LLM 기록 없음: 프롬프트 ID·버전·모델·온도가 없는 호출, 버전이 '<버전>@<해시 8자>' 형식이 아님, 판정의 프롬프트·모델이 단계 기록과 다름",
}


class Rejected(Exception):
    def __init__(self, code, msg):
        assert code in RULES or code == "DB", code
        super().__init__(f"{code} {RULES.get(code, 'DB 제약')}: {msg}")
        self.code = code


def sha256_bytes(b: bytes) -> str:
    return hashlib.sha256(b).hexdigest()


def dumps(x) -> str:
    return json.dumps(x, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def rel(p: Path) -> str:
    return str(p.resolve().relative_to(P.REPO))


# ───────── 기준표 읽기 ─────────

def parse_options(fact_id: str, question: str, allowed: str):
    """허용 값 칸 → (is_multi, [(label, kind)]). 읽는 법은 위 상수."""
    parts = [p.strip() for p in allowed.split(" / ")]
    multi = any(p.endswith(MULTI_MARK) for p in parts)
    out = []
    for p in parts:
        if p.endswith(MULTI_MARK):
            p = p[: -len(MULTI_MARK)]
        if p.startswith(EXPAND_PREFIX):
            out += [(it.strip(), "CHOICE") for it in question.split("? ", 1)[1].split(", ")]
        elif p in UNKNOWN_LABELS:
            out.append((p, "UNKNOWN"))
        elif fact_id.startswith("L"):
            out.append((p, "MATCH" if ("명시" in p or "포괄" in p) else "NO_MATCH"))
        elif p in DETAIL_LABELS or any(m in p for m in DETAIL_MARKERS):
            out.append((p, "DETAIL"))
        else:
            out.append((p, "CHOICE"))
    return multi, out


def read_fact_map(path: Path = FACT_MAP):
    with path.open(encoding="utf-8", newline="") as f:
        rows = list(csv.DictReader(f, delimiter="\t"))
    own = {r["사실ID"]: sorted({f"첨부{n}" for g in ATTACH_RE.findall(r["원장 조회"]) for n in g.split("·")},
                               key=lambda a: int(a[2:])) for r in rows}
    cat, opts = [], []
    for i, r in enumerate(rows, 1):
        fid = r["사실ID"]
        att = set(own[fid])
        for n in LREF_RE.findall(r["원장 조회"]):       # 'L2', 'L1 목적 칸' → 그 원장 조회의 첨부
            att |= set(own[f"L{n}"])
        multi, options = parse_options(fid, r["묻는 말(현업 담당자에게)"], r["허용 값"])
        cat.append(dict(fact_id=fid, ord=i, slot=r["슬롯"], is_common=int(r["슬롯"] == "공통"), fact_usage=r["쓰임"],
                        question=r["묻는 말(현업 담당자에게)"], allowed_raw=r["허용 값"], is_multi=int(multi),
                        ledger_lookup=r["원장 조회"], ledger_attachments=dumps(sorted(att, key=lambda a: int(a[2:]))),
                        on_unknown=r["모름일 때"], basis_keys=r["근거 키"], interp=r["해석 쟁점"]))
        opts += [dict(fact_id=fid, ord=j, label=l, kind=k) for j, (l, k) in enumerate(options, 1)]
    return cat, opts


def read_rule_set(path: Path = RULE_SET):
    rs = json.loads(path.read_text(encoding="utf-8"))
    slots, opts = [], []
    for s in rs["slot_dictionary"]:
        if s["purpose"] != "JUDGE":
            continue
        slots.append(dict(slot_key=s["slot_key"], label=s["label"], value_type=s["value_type"],
                          ask_condition=None if s["ask_condition"] is None else dumps(s["ask_condition"]),
                          rule_set_version=rs["version"], sort_order=s["sort_order"]))
        opts += [dict(slot_key=s["slot_key"], ord=j, value=v) for j, v in enumerate(s["allowed_values"], 1)]
    return rs, slots, opts


def ins(con, table, row):
    cols = list(row)
    cur = con.execute(f"INSERT INTO {table} ({','.join(cols)}) VALUES ({','.join('?' * len(cols))})", [row[c] for c in cols])
    return cur.lastrowid


def create_db(path: Path, fact_map: Path = FACT_MAP, rule_set: Path = RULE_SET, slot_rules_file: Path = SLOT_RULES):
    """빈 사안 DB + 기준표 + 규칙 3테이블(06 2.4). 있던 파일은 덮지 않는다(부르는 쪽이 새 경로를 준다).
    슬롯 변환 규칙은 넣기 전에 slot_rules.check로 기준표·KB와 대조하고, 어긋나면 DB를 만들지 않는다."""
    import slot_rules as sr   # 같은 폴더. slot_rules가 이 모듈을 읽으므로 여기서 부른다
    if path.exists():
        raise FileExistsError(path)
    doc = sr.load(slot_rules_file)
    errs = sr.check(doc, sr.catalog(fact_map, rule_set))
    if errs:
        raise ValueError("슬롯 변환 규칙 검사 실패: " + "; ".join(errs[:5]))
    con = sqlite3.connect(path)
    con.executescript(SCHEMA.read_text(encoding="utf-8"))
    cat, fopts = read_fact_map(fact_map)
    rs, slots, sopts = read_rule_set(rule_set)
    rs_head, risks = sr.rule_set_rows(rule_set)
    with con:
        ins(con, "catalog_sources", dict(name="fact_map", path=rel(fact_map), version=fact_map.stem.rsplit("_", 1)[-1],
                                         sha256=sha256_bytes(fact_map.read_bytes())))
        ins(con, "catalog_sources", dict(name="rule_set", path=rel(rule_set), version=rs["version"],
                                         sha256=sha256_bytes(rule_set.read_bytes())))
        ins(con, "catalog_sources", dict(name="slot_rules", path=rel(slot_rules_file), version=doc["version"],
                                         sha256=sha256_bytes(slot_rules_file.read_bytes())))
        for r in cat:
            ins(con, "fact_catalog", r)
        for r in fopts:
            ins(con, "fact_options", r)
        for r in slots:
            ins(con, "slot_catalog", r)
        for r in sopts:
            ins(con, "slot_options", r)
        ins(con, "rule_sets", rs_head)
        for r in risks:
            ins(con, "risk_rules", r)
        for r in sr.slot_rule_rows(doc):
            ins(con, "slot_rules", r)
    con.close()


# ───────── 저장 ─────────

def ro_uri(p: Path) -> str:
    return p.resolve().as_uri() + "?mode=ro"


class Store:
    """사안 DB 연결 + 지식 DB 두 개를 읽기 전용으로 붙임(kb, ledger)."""

    def __init__(self, path: Path, kb: Path = KB_DB, ledger: Path = LEDGER_DB):
        self.con = sqlite3.connect(Path(path).resolve().as_uri(), uri=True)
        self.con.row_factory = sqlite3.Row
        self.con.execute("PRAGMA foreign_keys = ON")
        self.con.execute("ATTACH DATABASE ? AS kb", (ro_uri(kb),))
        self.con.execute("ATTACH DATABASE ? AS ledger", (ro_uri(ledger),))
        self.rule_set = json.loads(RULE_SET.read_text(encoding="utf-8"))
        self.rule_grades = {r["rule_id"]: r["grade"] for r in self.rule_set["risk_rules"]}

    def close(self):
        self.con.close()

    # 작은 조회
    def one(self, sql, *a):
        return self.con.execute(sql, a).fetchone()

    def _write(self, fn):
        """검사를 모두 통과한 쓰기만 남긴다. DB 제약이 막으면(두 번째 벽) 'DB'로 거부."""
        try:
            with self.con:
                return fn()
        except sqlite3.IntegrityError as e:
            raise Rejected("DB", str(e)) from e

    def _step(self, case_id, seq):
        return self.one("SELECT * FROM steps WHERE case_id=? AND seq=?", case_id, seq)

    # ── 사안·단계·흐름·턴 ──
    def add_case(self, case_id, content, at, requester_id, rule_set_version, package_version, ledger_version=None, service_name=None):
        """사안 시작 때 쓰는 판 셋을 고정한다(06 2.4-3). 기준표·지식 DB에 없는 판이면 거부."""
        if not (requester_id or "").strip():
            raise Rejected("C01", "문의자(requester_id) 없음")
        if not package_version:
            raise Rejected("C11", "사안 꾸러미 판 없음")
        if self.one("SELECT 1 FROM kb.issue_packages WHERE version=?", package_version) is None:
            raise Rejected("C11", f"꾸러미 판 {package_version!r}가 privacyask.db에 없음")
        if rule_set_version != self.one("SELECT version FROM catalog_sources WHERE name='rule_set'")[0]:
            raise Rejected("C12", f"규칙 세트 {rule_set_version!r}")
        if ledger_version is not None and self.one("SELECT 1 FROM ledger.ledger_versions WHERE version_id=?", ledger_version) is None:
            raise Rejected("C12", f"처리방침 판 {ledger_version!r}가 ledger.db에 없음")
        return self._write(lambda: ins(self.con, "cases", dict(
            case_id=case_id, requester_id=requester_id, content=content, service_name=service_name, state="ASKING",
            rule_set_version=rule_set_version, package_version=package_version, ledger_version=ledger_version,
            created_at=at, updated_at=at)))

    def update_case(self, case_id, at, **fields):
        allowed = {"intake_type", "out_of_scope_kind", "state", "reference_date", "reference_date_basis", "service_name", "closed_at"}
        assert set(fields) <= allowed, set(fields) - allowed
        sets = ", ".join(f"{k}=?" for k in fields)
        self._write(lambda: self.con.execute(f"UPDATE cases SET {sets}, updated_at=? WHERE case_id=?",
                                             [*fields.values(), at, case_id]))

    def add_step(self, case_id, step, actor, name, input, output, at, flow_no=None, prompt_id=None, prompt_version=None,
                 model_name=None, temperature=None, code_version=None, status="OK", attempts=1):
        if actor == "LLM" and None in (prompt_id, prompt_version, model_name, temperature):
            raise Rejected("C17", f"{step} {name}")
        if prompt_version is not None and not PROMPT_VERSION_RE.match(prompt_version):
            raise Rejected("C17", f"{step} {name}: 프롬프트 버전 {prompt_version!r}는 '<버전>@<해시 8자>'가 아님")
        seq = self.one("SELECT coalesce(max(seq),0)+1 FROM steps WHERE case_id=?", case_id)[0]
        self._write(lambda: ins(self.con, "steps", dict(
            case_id=case_id, seq=seq, step=step, flow_no=flow_no, actor=actor, name=name, prompt_id=prompt_id,
            prompt_version=prompt_version, model_name=model_name, temperature=temperature, code_version=code_version,
            input_json=dumps(input), output_json=dumps(output), status=status, attempts=attempts, at=at)))
        return seq

    def add_flow(self, case_id, flow_no, label, origin, destination, created_step, service_name=None):
        if self._step(case_id, created_step) is None:
            raise Rejected("C01", f"흐름 {flow_no}: 단계 {created_step} 없음")
        self._write(lambda: ins(self.con, "flows", dict(case_id=case_id, flow_no=flow_no, label=label, origin=origin,
                                                         destination=destination, service_name=service_name,
                                                         created_step=created_step)))

    def options(self, fact_id):
        return {r["label"]: r["kind"] for r in self.con.execute("SELECT label, kind FROM fact_options WHERE fact_id=?", (fact_id,))}

    def add_turn(self, case_id, role, kind, text, choices, step_seq, at, flow_no=None, fact_id=None, ask_no=None, reply_to=None):
        if self._step(case_id, step_seq) is None:
            raise Rejected("C01", f"턴: 단계 {step_seq} 없음")
        if kind == "QUESTION":
            if self.one("SELECT 1 FROM fact_catalog WHERE fact_id=?", fact_id) is None:
                raise Rejected("C07", f"질문 사실 {fact_id}")
            self._check_flow(case_id, fact_id, flow_no)
            bad = [c for c in choices if c not in self.options(fact_id)]
            if bad:
                raise Rejected("C08", f"질문 보기 {bad}")
        else:
            q = self.one("SELECT * FROM turns WHERE case_id=? AND turn_no=? AND kind='QUESTION'", case_id, reply_to)
            if q is None:
                raise Rejected("C04", f"답이 받는 질문 턴 {reply_to} 없음")
            bad = [c for c in choices if c not in self.options(q["fact_id"])]
            if bad:
                raise Rejected("C08", f"답 {bad}는 {q['fact_id']} 보기에 없음")
            flow_no, fact_id = q["flow_no"], q["fact_id"]
        turn_no = self.one("SELECT coalesce(max(turn_no),0)+1 FROM turns WHERE case_id=?", case_id)[0]
        self._write(lambda: ins(self.con, "turns", dict(
            case_id=case_id, turn_no=turn_no, role=role, kind=kind, flow_no=flow_no,
            fact_id=fact_id if kind == "QUESTION" else None, ask_no=ask_no, reply_to=reply_to, text=text,
            choices_json=dumps(choices), step_seq=step_seq, at=at)))
        return turn_no

    def _check_flow(self, case_id, fact_id, flow_no):
        common = self.one("SELECT is_common FROM fact_catalog WHERE fact_id=?", fact_id)[0]
        if common and flow_no is not None:
            raise Rejected("C10", f"{fact_id}는 공통 사실인데 흐름 {flow_no}")
        if not common:
            if flow_no is None:
                raise Rejected("C10", f"{fact_id}는 흐름 사실인데 흐름 없음")
            if self.one("SELECT 1 FROM flows WHERE case_id=? AND flow_no=?", case_id, flow_no) is None:
                raise Rejected("C10", f"흐름 {flow_no} 없음")

    # ── 사실 ──
    def ledger_text(self, row_ids):
        out = []
        for rid in row_ids:
            r = self.one("SELECT fields_json FROM ledger.ledger_rows WHERE row_id=?", rid)
            out += [t for _, t in json.loads(r["fields_json"])]
        return "\n".join(out)

    def add_fact(self, case_id, fact_id, values, status, source_kind, source_step, at, flow_no=None, source_turn=None,
                 quote=None, ledger_rows=(), ledger_version=None, note=None):
        """values: ['예'] 또는 [['예(나라)', '베트남']] (자유 입력은 [보기, 적은 글]). 모름(NOT_FOUND)이면 []."""
        cat = self.one("SELECT * FROM fact_catalog WHERE fact_id=?", fact_id)
        if cat is None:
            raise Rejected("C07", fact_id)
        self._check_flow(case_id, fact_id, flow_no)
        # C01 출처
        if source_kind not in ("INQUIRY", "ANSWER", "LEDGER"):
            raise Rejected("C01", f"{fact_id} 출처 {source_kind!r}")
        step = self._step(case_id, source_step)
        if step is None:
            raise Rejected("C01", f"{fact_id} 단계 {source_step} 없음")
        # C04 답변 턴
        turn = None
        if source_kind == "ANSWER":
            turn = self.one("SELECT * FROM turns WHERE case_id=? AND turn_no=? AND kind='ANSWER'", case_id, source_turn)
            if turn is None:
                raise Rejected("C04", f"{fact_id} 턴 {source_turn}")
        elif source_turn is not None:
            raise Rejected("C04", f"{fact_id}: {source_kind} 출처에 턴 {source_turn}")
        # C08 값
        opts = self.options(fact_id)
        vals = [(v, None) if isinstance(v, str) else (v[0], v[1]) for v in values]
        for label, detail in vals:
            if label not in opts:
                raise Rejected("C08", f"{fact_id} 값 {label!r}")
            if (opts[label] == "DETAIL") != bool(detail):
                raise Rejected("C08", f"{fact_id} {label!r}: 자유 입력 {'누락' if opts[label] == 'DETAIL' else '잘못 붙음'}")
        if len(vals) > 1 and not cat["is_multi"]:
            raise Rejected("C08", f"{fact_id}는 단일 사실인데 값 {len(vals)}개")
        if len({l for l, _ in vals}) != len(vals):
            raise Rejected("C08", f"{fact_id} 값 중복")
        kinds = {opts[l] for l, _ in vals}
        # C09 확인 상태
        if status not in STATUSES:
            raise Rejected("C09", f"{fact_id} 상태 {status!r}")
        if (status == "NOT_FOUND") != (not vals):
            raise Rejected("C09", f"{fact_id}: 모름(NOT_FOUND)은 값이 없을 때만")
        if "UNKNOWN" in kinds and (len(vals) > 1 or status != "UNKNOWN"):
            raise Rejected("C09", f"{fact_id}: '잘 모르겠음'은 혼자, 상태는 현업 미확인")
        if status == "UNKNOWN" and ("UNKNOWN" not in kinds or source_kind != "ANSWER"):
            raise Rejected("C09", f"{fact_id}: 현업 미확인은 현업이 답 턴에서 '잘 모르겠음'을 고를 때만")
        if source_kind == "LEDGER":
            explicit = any(opts[l] == "MATCH" and EXPLICIT_MARK in l for l, _ in vals)
            want = "MATCHED" if explicit else "INFERRED"
            if status != want:
                raise Rejected("C09", f"{fact_id}: 원장 출처 {'명시 일치' if explicit else '포괄 일치·일치 없음'}는 {want} (지금 {status})")
        elif status == "MATCHED":
            raise Rejected("C09", f"{fact_id}: MATCHED는 원장 출처만")
        # C05 원장 출처 조건
        att = json.loads(cat["ledger_attachments"])
        rows = list(ledger_rows)
        if source_kind == "LEDGER":
            if not att:
                raise Rejected("C05", f"{fact_id}는 원장 조회가 없는 사실({cat['ledger_lookup']})")
            if step["step"] != "A3":
                raise Rejected("C05", f"{fact_id}: 원장 사실은 A3 단계가 적음(지금 {step['step']})")
            if kinds - {"NO_MATCH"} and not rows:
                raise Rejected("C05", f"{fact_id}: 일치 값인데 원장 행 없음")
            if kinds == {"NO_MATCH"} and rows:
                raise Rejected("C05", f"{fact_id}: 일치 없음인데 원장 행 {rows}")
        elif rows or ledger_version:
            raise Rejected("C05", f"{fact_id}: {source_kind} 출처에 원장 행·판")
        # C06 원장 행·판
        if source_kind == "LEDGER":
            if self.one("SELECT 1 FROM ledger.ledger_versions WHERE version_id=?", ledger_version) is None:
                raise Rejected("C06", f"처리방침 판 {ledger_version!r}")
            for rid in rows:
                r = self.one("SELECT version_id, attachment FROM ledger.ledger_rows WHERE row_id=?", rid)
                if r is None:
                    raise Rejected("C06", f"원장 행 {rid}")
                if r["version_id"] != ledger_version:
                    raise Rejected("C06", f"{rid} 판 {r['version_id']} ≠ {ledger_version}")
                if r["attachment"] not in att:
                    raise Rejected("C06", f"{fact_id}는 {att}만 볼 수 있음({rid})")
        # C02·C03 근거 문구
        needs_quote = status in ("CONFIRMED", "MATCHED", "INFERRED") and kinds != {"NO_MATCH"}
        if needs_quote and not (quote or "").strip():
            raise Rejected("C02", fact_id)
        if quote:
            if source_kind == "INQUIRY":
                src = self.one("SELECT content FROM cases WHERE case_id=?", case_id)["content"]
            elif source_kind == "ANSWER":
                src = turn["text"]
            else:
                src = self.ledger_text(rows)
            norm = (lambda s: re.sub(r"\s+", "", s)) if source_kind == "LEDGER" else (lambda s: s)  # PDF 줄바꿈
            for piece in quote.split(QUOTE_SEP):
                if norm(piece) not in norm(src):
                    raise Rejected("C03", f"{fact_id}: {piece!r}가 {source_kind} 글에 없음")

        rev = self.one("SELECT coalesce(max(rev),0)+1 FROM facts WHERE case_id=? AND fact_id=? AND flow_no IS ?",
                       case_id, fact_id, flow_no)[0]

        def write():
            fr = ins(self.con, "facts", dict(case_id=case_id, flow_no=flow_no, fact_id=fact_id, rev=rev, status=status,
                                             source_kind=source_kind, source_turn=source_turn, source_step=source_step,
                                             basis_quote=quote, ledger_version=ledger_version, note=note, recorded_at=at))
            for i, (label, detail) in enumerate(vals, 1):
                ins(self.con, "fact_values", dict(fact_row_id=fr, fact_id=fact_id, ord=i, label=label, detail=detail))
            for i, rid in enumerate(rows, 1):
                ins(self.con, "fact_ledger_rows", dict(fact_row_id=fr, ord=i, row_id=rid))
            return fr
        return self._write(write)

    # ── 인용 해시(키가 가리키는 그 판의 지문) ──
    def section_hash(self, section_id):
        """안내서 절: 그 절과 아래 절에 속한 청크 text_hash를 chunk_id 순으로 줄바꿈으로 이어 sha256."""
        hs = [r[0] for r in self.con.execute("""
            WITH RECURSIVE sub(id) AS (
              SELECT section_id FROM kb.guide_sections WHERE section_id = ?
              UNION ALL SELECT g.section_id FROM kb.guide_sections g JOIN sub ON g.parent_id = sub.id)
            SELECT text_hash FROM kb.kb_chunks WHERE section_id IN (SELECT id FROM sub) ORDER BY chunk_id""", (section_id,))]
        return sha256_bytes("\n".join(hs).encode("utf-8")) if hs else None

    def ledger_row_hash(self, row_id):
        r = self.one("SELECT fields_json FROM ledger.ledger_rows WHERE row_id=?", row_id)
        return None if r is None else sha256_bytes(r["fields_json"].encode("utf-8"))

    def citation_ok(self, kind, key, text_hash, ledger_version=None):
        if kind == "LAW_UNIT":
            return self.one("SELECT 1 FROM kb.kb_chunks WHERE unit_key=? AND text_hash=?", key, text_hash) is not None
        if kind == "ANNEX":
            return self.one("SELECT 1 FROM kb.kb_annexes WHERE annex_key=? AND text_hash=?", key, text_hash) is not None
        if kind == "GUIDE_SECTION":
            return self.section_hash(key) == text_hash
        if kind == "LEDGER_ROW":
            r = self.one("SELECT version_id FROM ledger.ledger_rows WHERE row_id=?", key)
            return r is not None and r["version_id"] == ledger_version and self.ledger_row_hash(key) == text_hash
        return False

    # ── 판정 ──
    def add_judgment(self, case_id, step_seq, reference_date, rule_set_version, package_version, fact_map_sha256,
                     model_name, temperature, prompt_versions, case_grade, exit_type, exit_reasons, conclusive, flags,
                     created_at, slots=(), issues=(), citations=(), ledger_version=None, review_bundle=None,
                     judged_turn=None, premise_note=PREMISE_NOTE):
        """slots: [{flow, slot, status, values, basis_facts, unknown_facts}]
        issues: [{flow, issue, package_id, grade, rule_ids, conditions, interp_flags, conclusive, opinion, actions, visible}]
        citations: [{issue (issues 목록 번호, 0부터) 또는 None, kind, key, text_hash, ledger_version, origin}]"""
        c = self.con
        # C16 연결
        st = self._step(case_id, step_seq)
        if st is None or st["step"] != "A8":
            raise Rejected("C16", f"판정 단계 {step_seq}는 A8이 아님")
        if judged_turn is not None and self.one("SELECT 1 FROM turns WHERE case_id=? AND turn_no=?", case_id, judged_turn) is None:
            raise Rejected("C16", f"턴 {judged_turn} 없음")
        # C11 꾸러미 판
        if not package_version:
            raise Rejected("C11", "꾸러미 판 없음")
        if self.one("SELECT 1 FROM kb.issue_packages WHERE version=?", package_version) is None:
            raise Rejected("C11", f"꾸러미 판 {package_version!r}가 privacyask.db에 없음")
        # C12 기준 판
        if rule_set_version != self.one("SELECT version FROM catalog_sources WHERE name='rule_set'")[0]:
            raise Rejected("C12", f"규칙 세트 {rule_set_version!r}")
        pin = self.one("SELECT rule_set_version, package_version, ledger_version FROM cases WHERE case_id=?", case_id)
        if pin is None:
            raise Rejected("C16", f"사안 {case_id} 없음")
        if rule_set_version != pin["rule_set_version"]:
            raise Rejected("C12", f"규칙 세트 {rule_set_version!r} ≠ 사안 고정값 {pin['rule_set_version']!r}")
        if package_version != pin["package_version"]:
            raise Rejected("C12", f"꾸러미 판 {package_version!r} ≠ 사안 고정값 {pin['package_version']!r}")
        if ledger_version is not None and ledger_version != pin["ledger_version"]:
            raise Rejected("C12", f"처리방침 판 {ledger_version!r} ≠ 사안 고정값 {pin['ledger_version']!r}")
        if fact_map_sha256 != self.one("SELECT sha256 FROM catalog_sources WHERE name='fact_map'")[0]:
            raise Rejected("C12", "대응표 해시가 기준표와 다름")
        used = {r[0] for r in c.execute("SELECT DISTINCT ledger_version FROM facts WHERE case_id=? AND ledger_version IS NOT NULL", (case_id,))}
        used |= {x.get("ledger_version") for x in citations if x["kind"] == "LEDGER_ROW"}
        if used and {ledger_version} != used:
            raise Rejected("C12", f"처리방침 판 {ledger_version!r}, 원장 사실·인용은 {sorted(map(str, used))}")
        if ledger_version and self.one("SELECT 1 FROM ledger.ledger_versions WHERE version_id=?", ledger_version) is None:
            raise Rejected("C12", f"처리방침 판 {ledger_version!r}가 ledger.db에 없음")
        # C17 프롬프트·모델
        llm = c.execute("SELECT DISTINCT prompt_id, prompt_version, model_name, temperature FROM steps WHERE case_id=? AND actor='LLM'",
                        (case_id,)).fetchall()
        if {(p["prompt_id"], p["version"]) for p in prompt_versions} != {(r[0], r[1]) for r in llm}:
            raise Rejected("C17", "판정의 프롬프트 목록이 이 사안 LLM 단계와 다름")
        if {(r[2], r[3]) for r in llm} - {(model_name, temperature)}:
            raise Rejected("C17", "판정의 모델·온도가 LLM 단계와 다름")
        # C14 슬롯
        stype = {r["slot_key"]: r["value_type"] for r in c.execute("SELECT * FROM slot_catalog")}
        sopts = {(r[0], r[1]) for r in c.execute("SELECT slot_key, value FROM slot_options")}
        flows = {r[0] for r in c.execute("SELECT flow_no FROM flows WHERE case_id=?", (case_id,))}
        seen = {}
        for s in slots:
            k, v, stt = s["slot"], s["values"], s["status"]
            if k not in stype:
                raise Rejected("C14", f"슬롯 {k!r}")
            if s["flow"] not in flows:
                raise Rejected("C10", f"슬롯 {k}: 흐름 {s['flow']} 없음")
            bad = [x for x in v if (k, x) not in sopts]
            if bad:
                raise Rejected("C14", f"{k} 값 {bad}")
            if stype[k] == "SINGLE" and len(v) > 1:
                raise Rejected("C14", f"{k}는 단일 슬롯인데 값 {v}")
            ok = {"CONFIRMED": bool(v) and not s["unknown_facts"], "PARTIAL": stype[k] == "MULTI" and bool(v) and bool(s["unknown_facts"]),
                  "UNKNOWN": not v and bool(s["unknown_facts"]), "SKIPPED": not v and not s["unknown_facts"]}.get(stt)
            if not ok:
                raise Rejected("C14", f"흐름 {s['flow']} {k}: 상태 {stt}와 값 {v}·미확인 {s['unknown_facts']}가 안 맞음")
            seen.setdefault(s["flow"], []).append(k)
            # C16 근거 사실
            for fr in s["basis_facts"]:
                r = self.one("SELECT case_id, flow_no FROM facts WHERE fact_row_id=?", fr)
                if r is None or r["case_id"] != case_id or r["flow_no"] not in (None, s["flow"]):
                    raise Rejected("C16", f"흐름 {s['flow']} {k}: 근거 사실 {fr}는 이 사안·흐름 것이 아님")
            for fid in s["unknown_facts"]:
                if self.one("SELECT 1 FROM facts_current WHERE case_id=? AND fact_id=? AND (flow_no=? OR flow_no IS NULL) AND status='UNKNOWN'",
                            case_id, fid, s["flow"]) is None:
                    raise Rejected("C16", f"흐름 {s['flow']} {k}: {fid}는 현업 미확인 사실이 아님")
        for f, ks in seen.items():
            if sorted(ks) != sorted(stype) :
                raise Rejected("C14", f"흐름 {f} 슬롯 {sorted(ks)} ≠ 8개")
        if slots and set(seen) != flows:
            raise Rejected("C14", f"슬롯이 없는 흐름 {sorted(flows - set(seen))}")
        # C15 쟁점
        grades = []
        for x in issues:
            p = self.one("SELECT conditions_json FROM kb.issue_packages WHERE version=? AND package_id=? AND issue_name=?",
                         package_version, x["package_id"], x["issue"])
            if p is None:
                raise Rejected("C15", f"{package_version} {x['package_id']} {x['issue']!r}")
            if x["flow"] not in flows:
                raise Rejected("C10", f"쟁점 {x['issue']}: 흐름 {x['flow']} 없음")
            conds = json.loads(p[0])
            cids = {d["id"] for d in conds}
            interp = {i["id"] for d in conds for i in d.get("interp", []) if i["status"] == "해석 쟁점"}
            if set(x["conditions"]) - cids:
                raise Rejected("C15", f"{x['issue']}: 조건 {sorted(set(x['conditions']) - cids)}")
            if set(x["interp_flags"]) - interp:
                raise Rejected("C15", f"{x['issue']}: 해석 쟁점 {sorted(set(x['interp_flags']) - interp)}는 {package_version}에서 해석 쟁점 아님")
            if set(x["rule_ids"]) - set(self.rule_grades):
                raise Rejected("C15", f"규칙 {sorted(set(x['rule_ids']) - set(self.rule_grades))}")
            grades.append(x["grade"])
        top = min(grades, key="HML".index) if grades else None
        if case_grade != top:
            raise Rejected("C15", f"사안 등급 {case_grade} ≠ 쟁점 최고 {top}")
        # C13 인용
        for x in citations:
            if not self.citation_ok(x["kind"], x["key"], x["text_hash"], x.get("ledger_version")):
                raise Rejected("C13", f"{x['kind']} {x['key']} {x['text_hash'][:12]}")
            if x.get("issue") is not None and not 0 <= x["issue"] < len(issues):
                raise Rejected("C13", f"{x['key']}: 쟁점 번호 {x['issue']}")

        def write():
            jid = ins(c, "case_judgments", dict(
                case_id=case_id, step_seq=step_seq, judged_turn=judged_turn, reference_date=reference_date,
                rule_set_version=rule_set_version, package_version=package_version, ledger_version=ledger_version,
                fact_map_sha256=fact_map_sha256, model_name=model_name, temperature=temperature,
                prompt_versions=dumps(prompt_versions), case_grade=case_grade, exit_type=exit_type,
                exit_reasons=dumps(exit_reasons), conclusive=int(conclusive), flags=dumps(flags),
                review_bundle=None if review_bundle is None else dumps(review_bundle), premise_note=premise_note,
                created_at=created_at))
            for s in slots:
                ins(c, "case_judgment_slots", dict(judgment_id=jid, case_id=case_id, flow_no=s["flow"], slot_key=s["slot"],
                                              status=s["status"], basis_facts=dumps(s["basis_facts"]),
                                              unknown_facts=dumps(s["unknown_facts"])))
                for i, v in enumerate(s["values"], 1):
                    ins(c, "case_judgment_slot_values", dict(judgment_id=jid, flow_no=s["flow"], slot_key=s["slot"], ord=i, value=v))
            ids = []
            for x in issues:
                ids.append(ins(c, "case_judgment_issues", dict(
                    judgment_id=jid, flow_no=x["flow"], issue_name=x["issue"], package_id=x["package_id"], grade=x["grade"],
                    rule_ids=dumps(x["rule_ids"]), conditions=dumps(x["conditions"]), interp_flags=dumps(x["interp_flags"]),
                    conclusive=int(x["conclusive"]), opinion=x.get("opinion"), actions=x.get("actions"),
                    visible_to_requester=int(x["visible"]))))
            for i, x in enumerate(citations, 1):
                ins(c, "case_judgment_citations", dict(
                    judgment_id=jid, issue_row_id=None if x.get("issue") is None else ids[x["issue"]], ord=i,
                    cite_kind=x["kind"], cite_key=x["key"], text_hash=x["text_hash"],
                    ledger_version=x.get("ledger_version"), origin=x["origin"]))
            return jid
        return self._write(write)


# ───────── 예시 사안 적재 ─────────

def resolve_fact(store: Store, case_id, flow_no, fact_id):
    """'F-T4' → 그 흐름(없으면 공통)의 지금 판 fact_row_id. 없으면 C16."""
    r = store.one("SELECT fact_row_id FROM facts_current WHERE case_id=? AND fact_id=? AND (flow_no=? OR flow_no IS NULL)",
                  case_id, fact_id, flow_no)
    if r is None:
        raise Rejected("C16", f"흐름 {flow_no}에 {fact_id} 사실 없음")
    return r[0]


def load_events(store: Store, spec: dict):
    """예시사안.json의 events를 순서대로 저장 함수에 넣는다. 단계 출력의 flows·facts는 그 단계가 적은 기록이다."""
    cid = spec["case_id"]
    for ev in spec["events"]:
        t = ev["type"]
        if t == "case":
            store.add_case(cid, ev["content"], ev["at"], ev["requester_id"], ev["rule_set_version"], ev["package_version"],
                           ev.get("ledger_version"), ev.get("service_name"))
        elif t == "case_update":
            store.update_case(cid, ev["at"], **ev["set"])
        elif t == "step":
            kw = {k: ev[k] for k in ("flow_no", "prompt_id", "prompt_version", "model_name", "temperature",
                                     "code_version", "status", "attempts") if k in ev}
            seq = store.add_step(cid, ev["step"], ev["actor"], ev["name"], ev["input"], ev["output"], ev["at"], **kw)
            for fl in ev["output"].get("flows", []):
                store.add_flow(cid, fl["flow"], fl["label"], fl["origin"], fl["destination"], seq, fl.get("service_name"))
            for f in ev["output"].get("facts", []):
                targets = f["flow"] if isinstance(f.get("flow"), list) else [f.get("flow")]
                for fl in targets:
                    store.add_fact(cid, f["fact"], f["values"], f["status"], f["source"], seq, ev["at"], flow_no=fl,
                                   source_turn=f.get("turn"), quote=f.get("quote"), ledger_rows=f.get("rows", ()),
                                   ledger_version=f.get("ledger_version"), note=f.get("note"))
        elif t == "turn":
            store.add_turn(cid, ev["role"], ev["kind"], ev["text"], ev["choices"], ev["step_seq"], ev["at"],
                           flow_no=ev.get("flow_no"), fact_id=ev.get("fact_id"), ask_no=ev.get("ask_no"),
                           reply_to=ev.get("reply_to"))
        elif t == "judgment":
            j = dict(ev)
            j.pop("type")
            if j.get("fact_map_sha256") == "기준표":   # 예시 파일에는 해시 대신 '기준표'라고 적어 둠: 지금 tsv 해시
                j["fact_map_sha256"] = store.one("SELECT sha256 FROM catalog_sources WHERE name='fact_map'")[0]
            slots = []
            for s in j.get("slots", []):
                s = dict(s)
                s["basis_facts"] = [resolve_fact(store, cid, s["flow"], f) for f in s["basis_facts"]]
                slots.append(s)
            j["slots"] = slots
            store.add_judgment(cid, **j)
        else:
            raise ValueError(t)


def build_example(out: Path = OUT, example: Path = EXAMPLE, kb: Path = KB_DB, ledger: Path = LEDGER_DB):
    tmp = out.with_name(out.name + ".tmp")
    if tmp.exists():
        tmp.unlink()
    create_db(tmp)
    s = Store(tmp, kb, ledger)
    try:
        load_events(s, json.loads(example.read_text(encoding="utf-8")))
    finally:
        s.close()
    tmp.replace(out)
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--example", default=str(EXAMPLE))
    ap.add_argument("--out", default=str(OUT))
    a = ap.parse_args()
    for p in (KB_DB, LEDGER_DB):
        if not p.exists():
            sys.exit(f"{p} 없음: 먼저 db/build_db.py, ledger/build_ledger.py")
    out = build_example(Path(a.out), Path(a.example))
    print(out, sha256_bytes(out.read_bytes()))


if __name__ == "__main__":
    main()
