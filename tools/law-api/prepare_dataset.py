#!/usr/bin/env python3
"""실제 수집본에서 사용 목적이 있는 조문·관계·서식 후보를 만든다. 법 적용을 확정하지 않는다."""
import argparse
import hashlib
import html
import json
import re
from pathlib import Path

from law_api import ROOT, ApiError, article_code, load_json, save_json


def as_list(value):
    return value if isinstance(value, list) else [] if value in (None, "") else [value]


def entries(value, pointer):
    if isinstance(value, list):
        return [(v, pointer + "/" + str(i)) for i, v in enumerate(value)]
    return [(value, pointer)] if value not in (None, "") else []


def code_from_node(node):
    number, branch = str(node.get("조문번호", "")), str(node.get("조문가지번호", "0") or "0")
    if not number.isdigit() or not branch.isdigit() or not int(number):
        return None
    return article_code(f"{int(number)}-{int(branch)}")


def label(code):
    return f"제{int(code[:4])}조" + (f"의{int(code[4:])}" if int(code[4:]) else "")


def content_text(node):
    if isinstance(node, str):
        return html.unescape(node)
    found = []
    if isinstance(node, dict):
        # 조문, 항, 호, 목의 순서를 유지한다. JSON 키의 수신 순서에 의존하지 않는다.
        for key in ("조문내용", "항내용", "호내용", "목내용", "항", "호", "목"):
            if key in node:
                found.append(content_text(node[key]))
    elif isinstance(node, list):
        found.extend(content_text(child) for child in node)
    return "\n".join(t for t in found if t)


def units(body, kind):
    if kind == "law":
        value = body.get("법령", {}).get("조문", {}).get("조문단위", [])
        for unit, pointer in entries(value, "/법령/조문/조문단위"):
            if unit.get("조문여부") != "조문":
                continue
            code = code_from_node(unit)
            if code:
                yield code, unit.get("조문제목", ""), unit, pointer
    else:
        value = body.get("AdmRulService", {}).get("조문내용", [])
        for unit, pointer in entries(value, "/AdmRulService/조문내용"):
            if not isinstance(unit, str):
                raise ApiError("행정규칙 조문내용의 형식이 바뀌었습니다.")
            match = re.match(r"^\s*제\s*(\d+)\s*조(?:의\s*(\d+))?\s*\(([^)]*)\)", unit)
            if match:
                yield article_code(f"{match[1]}-{match[2] or 0}"), match[3], unit, pointer


ANNEX_SELECTION = {
    "network-decree": {("별표", "0009", "00"): "신고·통지 등 관련 과태료 기준을 확인할 표",
                       ("별표", "0005", "02"): "침해사고 관련 이행강제금 기준 확인",
                       ("별표", "0005", "03"): "침해사고 관련 과징금 기준 확인"},
    "privacy-decree": {("별표", "0001", "05"): "개인정보 관련 과징금 기준 확인",
                       ("별표", "0002", "00"): "유출 통지·신고 등 관련 과태료 기준 확인"},
    "broadcast-decree": {("별표", "0001", "00"): "주요방송통신사업자의 제재 기준 확인",
                         ("별표", "0002", "00"): "방송통신재난 보고 등 관련 과태료 기준 확인"},
    "telecom-decree": {("별표", "0011", "00"): "서비스 중단 고지 등 관련 과태료 기준 확인"},
    "privacy-standard": {("별지", "0001", "00"): "개인정보 유출등 신고서의 제출 항목 확인"},
    "telecom-facilities": {("별표", "0001", "00"): "시설 등급에 따른 적용 조건 확인",
                           ("별표", "0002", "00"): "우회 통신경로 관련 관리 기준 확인",
                           ("서식", "0001", "00"): "시설 현황 관리 항목 확인"},
    "critical-guideline": {("별표", "0002", "00"): "지정 기반시설의 침해 대응·복구 고려사항 확인"},
    "critical-decree": {("별표", "0003", "00"): "지정 기반시설 관련 과태료 기준 확인"},
}


def scalar(value):
    if isinstance(value, list):
        # 서로 다른 복수 문서/버전을 임의로 조문과 짝짓지 않는다.
        if not value or any(isinstance(v, (list, dict)) for v in value):
            return None
        unique = list(dict.fromkeys(str(v) for v in value))
        return unique[0] if len(unique) == 1 else None
    return str(value) if value not in (None, "") and not isinstance(value, dict) else None


def build(source):
    source = Path(source)
    manifest = load_json(source / "manifest.json")
    if manifest.get("status") != "downloaded":
        raise ApiError("먼저 선택한 문서 수집을 완료하세요.")
    scope = {d["slug"]: d for d in load_json(ROOT / "project_scope.json")["documents"]}
    out = source / "prepared"
    selected_units, missing, annexes, annex_inventory, purposes = [], [], [], [], []
    focus_codes, versions = {}, {}
    for doc in manifest["documents"]:
        config = scope[doc["slug"]]
        directory = source / doc["slug"]
        body_path = directory / "body/response.json"
        body = load_json(body_path)
        request = load_json(directory / "body/request.json")
        if hashlib.sha256(body_path.read_bytes()).hexdigest() != request["sha256"]:
            raise ApiError("원문 해시가 일치하지 않습니다: " + doc["name"])
        selected = load_json(directory / "selected.json")
        version = str(selected["법령일련번호"] if doc["kind"] == "law" else selected["행정규칙일련번호"])
        versions[version] = doc["slug"]
        focus = {article_code(x) for x in config["article_focus"]}
        found = set()
        web_url = ("https://www.law.go.kr/LSW/lsInfoP.do?lsiSeq=" if doc["kind"] == "law" else
                   "https://www.law.go.kr/LSW/admRulLsInfoP.do?admRulSeq=") + version
        for code, title, unit, pointer in units(body, doc["kind"]):
            definition = title.strip() in ("정의", "용어의 정의", "적용범위", "적용 범위") and int(code[:4]) <= 3
            if code not in focus and not definition:
                continue
            found.add(code)
            selected_units.append({
                "document_slug": doc["slug"], "document_name": doc["name"], "document_kind": doc["kind"],
                "version_serial": version, "document_effective_date": str(selected.get("시행일자", "")),
                "article_code": code, "article_label": label(code), "article_title": title,
                "article_effective_date": unit.get("조문시행일자") if isinstance(unit, dict) else None,
                "selection_reason": "기존 조사 및 서비스 의무에 연결된 우선 확인 조문" if code in focus else "적용 대상·용어 확인에 필요한 정의·범위 조문",
                "downstream_uses": config["downstream_uses"], "review_status": "retrieval_candidate_not_final_legal_rule",
                "source_file": str(body_path.relative_to(source)), "source_json_pointer": pointer,
                "source_sha256": request["sha256"], "web_source": web_url,
                "text": content_text(unit), "source_unit": unit,
            })
        focus_codes[doc["slug"]] = found
        for code in focus - found:
            missing.append({"slug": doc["slug"], "article": label(code), "reason": "지정한 조문을 수집 본문에서 찾지 못함"})
        purposes.append({"name": doc["name"], "slug": doc["slug"], "kind": doc["kind"], "version_serial": version,
                         "effective_date": str(selected.get("시행일자", "")), "collection_reason": config["collection_reason"],
                         "downstream_uses": config["downstream_uses"], "selected_articles": [label(c) for c in sorted(found)],
                         "web_source": web_url, "fetched_at": request["fetched_at"], "source_sha256": request["sha256"]})
        key = "법령" if doc["kind"] == "law" else "AdmRulService"
        for annex, pointer in entries(body[key].get("별표", {}).get("별표단위", []), f"/{key}/별표/별표단위"):
            identifier = (str(annex.get("별표구분")), str(annex.get("별표번호", "")).zfill(4), str(annex.get("별표가지번호", "00")).zfill(2))
            reason = ANNEX_SELECTION.get(doc["slug"], {}).get(identifier)
            item = {"document_slug": doc["slug"], "document_name": doc["name"], "version_serial": version,
                    "title": annex.get("별표제목"), "type": identifier[0], "number": identifier[1], "branch": identifier[2],
                    "source_file": str(body_path.relative_to(source)), "source_json_pointer": pointer,
                    "selected": bool(reason), "selection_reason": reason or "우선 사고 대응 쟁점의 서식·기준표로 선정하지 않음",
                    "attachment_files_downloaded": False, "text_provided_by_api": bool(annex.get("별표내용"))}
            annex_inventory.append(item)
            if reason:
                annexes.append({**item, "review_status": "source_candidate_requires_applicable_rows_review", "source_unit": annex})
    edges, ambiguous = [], []
    for doc in manifest["documents"]:
        path = source / "relationships" / doc["slug"] / "lsDelegated/response.json"
        if not path.exists():
            continue
        data = load_json(path)
        base = data.get("lsDelegated", {}).get("법령", {})
        rows = base.get("위임조문정보", [])
        for row, row_pointer in entries(rows, "/lsDelegated/법령/위임조문정보"):
            code = code_from_node(row.get("조정보", {}))
            if code not in focus_codes.get(doc["slug"], set()):
                continue
            for group, group_pointer in entries(row.get("위임정보", []), row_pointer + "/위임정보"):
                common = {"source_slug": doc["slug"], "source_document": doc["name"], "source_article_code": code,
                          "source_article": label(code), "source_article_title": row.get("조정보", {}).get("조문제목"),
                          "source_version": doc["selection"]["MST"], "source_file": str(path.relative_to(source)),
                          "source_json_pointer": group_pointer}
                family = next((f for f in ("위임법령", "위임행정규칙", "위임자치법규", "위임규정", "조약") if f + "제목" in group), None)
                title = scalar(group.get((family or "") + "제목"))
                serial = scalar(group.get((family or "") + "일련번호"))
                relation = scalar(group.get("위임구분"))
                if not family or not title or not serial or not relation:
                    ambiguous.append({**common, "reason": "JSON이 복수 대상 법령·버전을 같은 묶음에 반환하여 세부 조문과의 대응을 단정할 수 없음", "source_group": group})
                    continue
                details = group.get(family + "조문정보")
                detail_pointer = group_pointer + "/" + family + "조문정보" if details is not None else group_pointer
                for detail, pointer in entries(details if details is not None else {}, detail_pointer):
                    if any(isinstance(v, (dict, list)) for v in detail.values()):
                        ambiguous.append({**common, "reason": "세부 조문 정보가 복수 값을 포함하므로 임의 연결하지 않음", "source_group": group})
                        continue
                    edges.append({**common, "relationship_type": relation, "target_document": title,
                                  "target_version_serial": serial, "target_collected_slug": versions.get(serial),
                                  "target_article_number": detail.get(family + "조문번호"),
                                  "target_article_branch": detail.get(family + "조문가지번호"),
                                  "target_article_title": detail.get(family + "조문제목"),
                                  "source_location": detail.get("조항호목"), "link_text": detail.get("링크텍스트"),
                                  "line_text": detail.get("라인텍스트"), "detail_pointer": pointer,
                                  "meaning": "공식 위임·인용 연결이며 사고별 법 적용·신고 갈음 판단을 의미하지 않음"})
    out.mkdir(parents=True, exist_ok=True)
    (out / "조문_검색후보.jsonl").write_text("".join(json.dumps(u, ensure_ascii=False) + "\n" for u in selected_units), encoding="utf-8")
    save_json(out / "수집대상_용도.json", purposes)
    save_json(out / "법령연결.json", edges)
    save_json(out / "법령연결_확인필요.json", ambiguous)
    save_json(out / "선정_별표서식.json", annexes)
    save_json(out / "전체_별표서식_목록.json", annex_inventory)
    save_json(out / "판례_결정례_수집기준.json", {"body_downloaded": 0, "list_requests": 0,
              "reason": "이번 수집은 의무·기한 원문 확보 단계. 판례는 원문만으로 해결되지 않는 구체적 쟁점이 확인될 때 선별 수집.",
              "policy": load_json(ROOT / "collection_policy.json")})
    report = {"document_count": len(purposes), "selected_article_count": len(selected_units),
              "relationship_edges": len(edges), "relationship_groups_needing_review": len(ambiguous),
              "annexes_in_source_documents": len(annex_inventory), "selected_annex_count": len(annexes),
              "selected_annex_without_text": [a["title"] for a in annexes if not a["text_provided_by_api"]],
              "missing_focus_articles": missing, "case_body_count": 0,
              "legal_review": "원문 수집·위치 검증 및 사용 목적에 따른 후보 선별. 사고별 적용 판단이나 의무표 검토 완료가 아님."}
    save_json(out / "검증결과.json", report)
    return report


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, required=True)
    args = parser.parse_args()
    try:
        print(json.dumps(build(args.input), ensure_ascii=False, indent=2))
    except ApiError as exc:
        parser.exit(2, str(exc) + "\n")
