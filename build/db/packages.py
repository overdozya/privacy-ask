"""쟁점 꾸러미(D6): data/수집/꾸러미_v*.json → issue_packages, package_items, package_item_units(버전마다 행).

build_db.py가 다른 테이블을 다 넣은 뒤 같은 연결로 부른다. 입력 파일이 규칙을 어기면 AssertionError로
빌드를 멈춘다(대상 키가 DB에 없음, 역할-사용 정책 규칙 위반 등). 현재 시각·난수를 쓰지 않는다.

규칙
- 대상 키: LAW_UNIT = 현행 청크의 unit_key 그대로, 또는 조 접두('법령키|조|', 그 조의 모든 현행 청크).
  ANNEX = 현행 별표 annex_key. GUIDE_SECTION = section_id(깊이 3 이상: 문서 뿌리·장은 통째로 넣지 않음).
- 즉답 근거(answer_basis=1): 문서가 ANSWER_BASIS이고 대상이 그 문서 use_scope 안.
  법령은 펼친 청크 키가 모두 범위 접두로 시작, 안내서는 그 절이나 위 절이 범위 목록에 있음.
  별표는 use_scope가 조문 접두만 갖고 있어서, 별표 연결(kb_links 별표)로 이어진 조문이 범위 안이면 범위 안으로 본다.
- 역할 핵심/요건/예외/절차는 즉답 근거 항목만 가질 수 있다. 즉답 근거가 아닌 항목은 해석만 갖고 basis_note에 이유가 붙는다.
"""
from __future__ import annotations

import hashlib
import json
import re
from pathlib import Path

KINDS = ("LAW_UNIT", "ANNEX", "GUIDE_SECTION")  # 사규는 나중에 'POLICY_UNIT'(법령과 같은 키 구조)을 더한다
ROLES = ("핵심", "요건", "예외", "절차", "해석")
BASIS_ROLES = ("핵심", "요건", "예외", "절차")
SOURCES = ("현업", "팀P3", "위임연결", "본문참조", "안내서목차", "D6계획", "우리판단", "대응표")  # 대응표는 v2부터
COND_KINDS = ("trigger", "하위", "제안", "사실 조건")  # 사실 조건은 v2부터(대응표 결정 8·9)
TRIGGER_STATUS = ("현업", "제안", "사실 조건")
INTERP_STATUS = ("사실로 풂", "해석 쟁점", "뺌")  # 대응표 결정 10(① / ② / ③). v1 후보에는 상태가 없다
FACT_ID = re.compile(r"F-[A-Z]+\d+|L\d+")  # F-…: 현업에게 묻는 사실, L…: 원장에서 찾는 사실(대응표 .tsv)


def _check_facts(facts, where):
    assert facts, f"사실 조건에 facts가 없음: {where}"
    for f in facts:
        assert FACT_ID.fullmatch(f["fact"]), f"사실 ID 모양이 틀림: {where} {f['fact']}"
        assert f["op"] in ("eq", "in"), where
        assert f["value"] if f["op"] == "eq" else isinstance(f["value"], list) and f["value"], where


def is_article_prefix(key: str) -> bool:
    """'법령키|조|' 꼴(조 전체)인가. 정확한 unit_key는 '|'가 4개다."""
    return key.count("|") == 2 and key.endswith("|")


def _scope(con, doc_id):
    r = con.execute("SELECT use_policy, use_scope, source_type FROM kb_documents WHERE doc_id=?", (doc_id,)).fetchone()
    return r[0], json.loads(r[1]) if r[1] else [], r[2]


def _section_ancestors(con, sid):
    out = []
    while sid:
        out.append(sid)
        sid = con.execute("SELECT parent_id FROM guide_sections WHERE section_id=?", (sid,)).fetchone()[0]
    return out


def _resolve(con, kind, key):
    """대상 → (doc_id, [(unit_key, chunk_id, annex_id, 글자 수)], 즉답 근거 여부, 근거 아님 이유)."""
    if kind == "LAW_UNIT":
        if is_article_prefix(key):
            rows = con.execute("SELECT unit_key, chunk_id, doc_id, length(text) FROM kb_chunks WHERE status='CURRENT' "
                               "AND substr(unit_key,1,?)=? ORDER BY chunk_id", (len(key), key)).fetchall()
        else:
            rows = con.execute("SELECT unit_key, chunk_id, doc_id, length(text) FROM kb_chunks WHERE status='CURRENT' "
                               "AND unit_key=? ORDER BY chunk_id", (key,)).fetchall()
        assert rows, f"LAW_UNIT 키가 DB 현행 청크에 없음: {key}"
        docs = {r[2] for r in rows}
        assert len(docs) == 1, key
        doc = docs.pop()
        policy, scope, stype = _scope(con, doc)
        assert stype == "LAW", key
        units = [(r[0], r[1], None, r[3]) for r in rows]
        if policy != "ANSWER_BASIS":
            return doc, units, 0, f"즉답 근거 아님: 문서 사용 정책 {policy}"
        inside = all(any(u[0].startswith(p) for p in scope) for u in units)
        return doc, units, int(inside), None if inside else "즉답 근거 아님: 사용 범위(use_scope) 밖 조문"
    if kind == "ANNEX":
        r = con.execute("SELECT annex_id, doc_id, length(coalesce(text_rows, text)) FROM kb_annexes "
                        "WHERE status='CURRENT' AND annex_key=?", (key,)).fetchone()
        assert r, f"ANNEX 키가 DB 현행 별표에 없음: {key}"
        annex_id, doc, n = r
        policy, scope, _ = _scope(con, doc)
        units = [(key, None, annex_id, n)]
        if policy != "ANSWER_BASIS":
            return doc, units, 0, f"즉답 근거 아님: 문서 사용 정책 {policy}"
        linked = [x[0] for x in con.execute(
            "SELECT src_key FROM kb_links WHERE link_type='별표' AND dst_annex_id=? AND src_chunk_id IS NOT NULL "
            "UNION SELECT dst_key FROM kb_links WHERE link_type='별표' AND src_annex_id=? AND dst_chunk_id IS NOT NULL",
            (annex_id, annex_id))]
        inside = any(key.startswith(p) for p in scope) or any(l.startswith(p) for l in linked for p in scope)
        return doc, units, int(inside), None if inside else "즉답 근거 아님: 별표와 이어진 조문이 사용 범위 밖"
    if kind == "GUIDE_SECTION":
        r = con.execute("SELECT doc_id, depth FROM guide_sections WHERE section_id=?", (key,)).fetchone()
        assert r, f"GUIDE_SECTION 절 ID가 DB에 없음: {key}"
        doc, depth = r
        assert depth >= 3, f"문서 뿌리나 장(깊이 {depth})은 통째로 넣지 않는다: {key}"
        rows = con.execute(
            "WITH RECURSIVE sub(id) AS (SELECT ? UNION ALL SELECT s.section_id FROM guide_sections s JOIN sub ON s.parent_id=sub.id) "
            "SELECT c.unit_key, c.chunk_id, length(c.text) FROM kb_chunks c WHERE c.section_id IN (SELECT id FROM sub) "
            "ORDER BY c.chunk_id", (key,)).fetchall()
        assert rows, key
        policy, scope, _ = _scope(con, doc)
        units = [(x[0], x[1], None, x[2]) for x in rows]
        if policy != "ANSWER_BASIS":
            return doc, units, 0, f"즉답 근거 아님: 문서 사용 정책 {policy}"
        inside = any(a in scope for a in _section_ancestors(con, key))
        return doc, units, int(inside), None if inside else "즉답 근거 아님: 사용 범위(use_scope) 밖 절"
    raise AssertionError(f"모르는 대상 종류: {kind}")


def load(path: Path):
    raw = path.read_bytes()
    return json.loads(raw.decode("utf-8")), hashlib.sha256(raw).hexdigest()


def content_sha256(packages) -> str:
    """꾸러미 내용 지문(packages 목록만, 키 정렬). 고정 확인용: 설명·메모 칸을 고쳐도 바뀌지 않는다."""
    s = json.dumps(packages, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(s.encode("utf-8")).hexdigest()


def check_frozen(p):
    """고정된 버전이면 내용이 고정 때와 같아야 한다. 바꾸려면 version을 올린다(v1 → v2).
    반환: (상태, 정한 사람, 정한 날, 내용 지문)."""
    ver, frozen, h = p["version"], p.get("frozen", {}), content_sha256(p["packages"])
    if ver in frozen:
        f = frozen[ver]
        assert p["status"] == "고정", f"{ver}는 frozen에 있으니 status는 '고정'이어야 한다"
        assert h == f["packages_sha256"], (
            f"고정된 꾸러미 {ver}의 내용이 바뀜: version을 새로 올리고(예 v2) status를 '사용자 검토 전'으로 둔다")
        return "고정", f["decided_by"], f["decided_at"], h
    assert p["status"] != "고정", f"{ver}는 frozen에 없으니 '고정'일 수 없다"
    return p["status"], None, None, h


def build_packages(con, path: Path, current: bool = True, item_id_start: int = 0):
    """입력 파일 하나(한 버전)를 검사하고 세 테이블에 더한다. 여러 버전을 차례로 부를 수 있다
    (item_id는 item_id_start 다음부터). 반환: 꾸러미 수, 항목 수, 마지막 item_id 등."""
    p, digest = load(path)
    status, decided_by, decided_at, content = check_frozen(p)
    pk = p["packages"]
    ids = [x["package_id"] for x in pk]
    assert len(ids) == len(set(ids)) and len({x["issue"] for x in pk}) == len(pk)
    interps = [q for x in pk for c in x["conditions"] for q in c.get("interp", [])]
    if any("status" in q for q in interps):  # 상태가 있는 버전(v2~)은 모든 후보가 ID·상태를 가진다
        assert all(q.get("status") in INTERP_STATUS and q.get("id") for q in interps), "해석 후보 상태·ID 빠짐"
        iids = [q["id"] for q in interps]
        assert len(iids) == len(set(iids)), "해석 후보 ID 중복"
        for q in interps:
            assert all(FACT_ID.fullmatch(f) for f in q["facts"]), q["id"]
            if q["status"] == "사실로 풂":
                assert q["facts"], f"사실로 푼 후보에 사실 ID가 없음: {q['id']}"
            if "merged_into" in q:
                assert q["merged_into"] in iids, q["id"]
            for f in q.get("on_when", []):
                _check_facts([f], q["id"])
    else:
        iids = []
    pkg_rows, item_rows, unit_rows = [], [], []
    item_id = item_id_start
    for x in pk:
        assert x["trigger_status"] in TRIGGER_STATUS, x["package_id"]
        for o in x.get("opened_by", []):
            pid, cid = o.split(".")
            assert any(c["id"] == cid and c.get("opens") == x["package_id"]
                       for y in pk if y["package_id"] == pid for c in y["conditions"]), (x["package_id"], o)
        conds = {c["id"]: c for c in x["conditions"]}
        assert len(conds) == len(x["conditions"]), x["package_id"]
        for c in x["conditions"]:
            assert c["kind"] in COND_KINDS, (x["package_id"], c["id"])
            if c["kind"] == "하위" or (c["kind"] == "사실 조건" and "parent" in c):
                assert all(par in conds for par in c["parent"].split("/")), (x["package_id"], c["id"])
            if c["kind"] == "사실 조건":
                _check_facts(c.get("facts"), f"{x['package_id']} {c['id']}")
                if "opens" in c:
                    assert c["opens"] in ids and c["opens"] != x["package_id"], (x["package_id"], c["id"])
                for off in c.get("interp_off", []):
                    assert off in iids, (x["package_id"], c["id"], off)
            for q in c.get("interp", []):
                assert q["q"] and q["phrase"], (x["package_id"], c["id"])
                kind = "ANNEX" if "|별표|" in q["basis"] else "LAW_UNIT"
                _, units, _, _ = _resolve(con, kind, q["basis"])
                text = "".join(con.execute("SELECT text FROM kb_chunks WHERE chunk_id=?", (u[1],)).fetchone()[0]
                               for u in units)
                assert q["phrase"] in text, f"해석 후보 근거 문구가 조문에 없음: {x['package_id']} {c['id']} {q['basis']}"
        seen = set()
        pkg_units = {}
        sums = {"LAW_UNIT": 0, "ANNEX": 0, "GUIDE_SECTION": 0}
        basis_chars = 0
        for ord_, it in enumerate(x["items"], 1):
            kind, key = it["kind"], it["key"]
            assert kind in KINDS, (x["package_id"], key)
            assert it["role"] in ROLES, (x["package_id"], key)
            assert it["source"] in SOURCES, (x["package_id"], key)
            assert it["why"].strip(), (x["package_id"], key)
            assert (kind, key) not in seen, f"같은 꾸러미에 같은 대상 두 번: {x['package_id']} {key}"
            seen.add((kind, key))
            ic = it.get("conds")
            if ic is not None:
                assert ic and set(ic) <= set(conds), (x["package_id"], key, ic)
            doc, units, basis, note = _resolve(con, kind, key)
            if it["role"] in BASIS_ROLES:
                assert basis == 1, f"역할 {it['role']}은 즉답 근거 범위 안 항목만: {x['package_id']} {key} ({note})"
            item_id += 1
            chars = sum(u[3] for u in units)
            item_rows.append(dict(item_id=item_id, version=p["version"], package_id=x["package_id"], ord=ord_, target_kind=kind,
                                  target_key=key, target_doc_id=doc, role=it["role"], reason=it["why"],
                                  source=it["source"], conds=json.dumps(ic, ensure_ascii=False) if ic else None,
                                  answer_basis=basis, basis_note=note, unit_count=len(units), char_count=chars))
            for j, u in enumerate(units, 1):
                unit_rows.append(dict(item_id=item_id, ord=j, unit_key=u[0], chunk_id=u[1], annex_id=u[2]))
                uk = ("a", u[2]) if u[2] else ("c", u[1])
                if uk not in pkg_units:
                    pkg_units[uk] = u[3]
                    sums[kind] += u[3]
                    if basis:
                        basis_chars += u[3]
        pkg_rows.append(dict(
            package_id=x["package_id"], version=p["version"], issue_name=x["issue"], origin=x["origin"],
            trigger_json=json.dumps(x["trigger"], ensure_ascii=False), trigger_status=x["trigger_status"],
            trigger_note=x.get("trigger_note"), rules_json=json.dumps(x["rules"], ensure_ascii=False),
            conditions_json=json.dumps(x["conditions"], ensure_ascii=False),
            excluded_json=json.dumps(x.get("excluded", []), ensure_ascii=False),
            item_count=len(x["items"]), unit_count=len(pkg_units), law_chars=sums["LAW_UNIT"],
            annex_chars=sums["ANNEX"], guide_chars=sums["GUIDE_SECTION"], total_chars=sum(sums.values()),
            basis_chars=basis_chars, input_sha256=digest, status=status, decided_by=decided_by,
            decided_at=decided_at, content_sha256=content, is_current=int(current),
            opened_by=json.dumps(x["opened_by"], ensure_ascii=False) if x.get("opened_by") else None))
    cols = list(pkg_rows[0])
    con.executemany(f"INSERT INTO issue_packages ({','.join(cols)}) VALUES ({','.join('?' * len(cols))})",
                    [[r[c] for c in cols] for r in pkg_rows])
    cols = list(item_rows[0])
    con.executemany(f"INSERT INTO package_items ({','.join(cols)}) VALUES ({','.join('?' * len(cols))})",
                    [[r[c] for c in cols] for r in item_rows])
    cols = list(unit_rows[0])
    con.executemany(f"INSERT INTO package_item_units ({','.join(cols)}) VALUES ({','.join('?' * len(cols))})",
                    [[r[c] for c in cols] for r in unit_rows])
    return dict(packages=len(pkg_rows), package_items=len(item_rows), package_sha256=digest,
                version=p["version"], status=status, last_item_id=item_id)
