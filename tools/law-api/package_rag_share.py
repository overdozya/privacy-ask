#!/usr/bin/env python3
"""검증된 수집본을 법령별 RAG 자료로 정리한다. 네트워크 요청이나 법 적용 추론은 하지 않는다."""
import argparse
import hashlib
import html
import json
import re
import tempfile
import zipfile
from collections import Counter
from datetime import datetime
from pathlib import Path
from urllib.parse import urljoin

from law_api import ApiError, KST, ROOT, load_json, load_oc, save_json, validate_body
from prepare_dataset import as_list, code_from_node, content_text, entries, label


def require(condition, message):
    if not condition:
        raise ApiError(message)


def iso_date(value):
    if value in (None, ""):
        return None
    value = str(value)
    require(bool(re.fullmatch(r"\d{8}", value)), "날짜 형식 확인 필요: " + value)
    return datetime.strptime(value, "%Y%m%d").date().isoformat()


def lines_text(value):
    """배열의 줄 순서와 표 안의 공백을 유지한다. 문자열을 요약하거나 잘라내지 않는다."""
    if value in (None, ""):
        return ""
    if isinstance(value, str):
        return html.unescape(value)
    if isinstance(value, list):
        return "\n".join(lines_text(item) for item in value)
    raise ApiError("예상하지 못한 텍스트 구조: " + type(value).__name__)


def record_id(slug, version, kind, key):
    return f"{slug}:{version}:{kind}:{key}"


def source_url(kind, version):
    return ("https://www.law.go.kr/LSW/lsInfoP.do?lsiSeq=" if kind == "law" else
            "https://www.law.go.kr/LSW/admRulLsInfoP.do?admRulSeq=") + str(version)


def links_from_node(node):
    result = []
    for key, value in node.items():
        if "링크" not in key:
            continue
        for link in as_list(value):
            require(isinstance(link, str), "첨부 링크 구조 확인 필요")
            if link:
                result.append({"source_field": key, "url": urljoin("https://www.law.go.kr", link)})
    return result


def body_record_ids(doc):
    return {r["id"] for key in ("articles", "supplementary_provisions", "annexes", "headings", "amendment_materials")
            for r in doc[key]}


def make_document(item, base, scope, focus_articles, focus_annexes):
    slug, kind = item["slug"], item["kind"]
    raw = (base / slug / "body/response.json").read_bytes()
    request = load_json(base / slug / "body/request.json")
    selected = load_json(base / slug / "selected.json")
    require(request.get("http_status") == 200 and not request.get("synthetic_test_fixture"), "실제 성공 응답이 아님")
    require(hashlib.sha256(raw).hexdigest() == request["sha256"], slug + ": 원문 해시 불일치")
    body = json.loads(raw)
    validate_body(body, selected, kind)
    version = str(selected["법령일련번호"] if kind == "law" else selected["행정규칙일련번호"])
    root_key = "법령" if kind == "law" else "AdmRulService"
    root = body[root_key]
    root_pointer = "/" + root_key
    doc = {
        "schema_version": "1.0",
        "document": {
            "id": slug,
            "name": item["name"],
            "kind": kind,
            "legal_type": selected.get("법령구분명", selected.get("행정규칙종류")),
            "source_document_id": selected.get("법령ID", selected.get("행정규칙ID")),
            "version_serial": version,
            "effective_date": iso_date(selected.get("시행일자")),
            "promulgated_date": iso_date(selected.get("공포일자", selected.get("발령일자"))),
            "promulgation_number": selected.get("공포번호", selected.get("발령번호")),
            "competent_authority": selected.get("소관부처명"),
            "source_url": source_url(kind, version),
            "fetched_at": request["fetched_at"],
            "source_response_sha256": request["sha256"],
            "project_group": item["group"],
            "collection_reason": scope["collection_reason"],
            "intended_uses": scope["downstream_uses"],
        },
        "articles": [],
        "supplementary_provisions": [],
        "annexes": [],
        "headings": [],
        "amendment_materials": [],
        "attachment_links": links_from_node(root.get("첨부파일", {})),
    }
    path = root_pointer + ("/조문/조문단위" if kind == "law" else "/조문내용")
    values = root.get("조문", {}).get("조문단위", []) if kind == "law" else root.get("조문내용", [])
    seen_codes = set()
    for position, (unit, pointer) in enumerate(entries(values, path), start=1):
        if kind == "law":
            code = code_from_node(unit) if unit.get("조문여부") == "조문" else None
            title = unit.get("조문제목", "")
            text = content_text(unit)
            effective = iso_date(unit.get("조문시행일자"))
        else:
            require(isinstance(unit, str), slug + ": 행정규칙 조문 형식 변경")
            match = re.match(r"^\s*제\s*(\d+)\s*조(?:의\s*(\d+))?", unit)
            code = f"{int(match[1]):04d}{int(match[2] or 0):02d}" if match else None
            title_match = re.match(r"^\s*제\s*\d+\s*조(?:의\s*\d+)?\s*\(([^)]*)\)", unit)
            title = title_match[1] if title_match else ""
            text = content_text(unit)
            effective = None  # 개별 조문 시행일을 문서 시행일로 추정하지 않는다.
        if not code:
            require(kind != "law" or unit.get("조문여부") != "조문", slug + ": 조 번호 확인 필요")
            doc["headings"].append({"id": record_id(slug, version, "heading", position),
                                    "body_position": position, "text": text, "source_json_pointer": pointer})
            continue
        require(code not in seen_codes, slug + ": 같은 버전의 중복 조문")
        seen_codes.add(code)
        focus = focus_articles.get((slug, code))
        row = {
            "id": record_id(slug, version, "article", code),
            "article_code": code,
            "label": label(code),
            "title": title,
            "body_position": position,
            "text": text,
            "article_effective_date": effective,
            "project_focus": bool(focus),
            "source_json_pointer": pointer,
        }
        if focus:
            require(text == focus["text"], slug + ": 기존 선정 조문과 텍스트 불일치")
            row["focus_reason"] = focus["selection_reason"]
        if isinstance(unit, dict) and unit.get("조문참고자료"):
            row["source_notes"] = unit["조문참고자료"]
        doc["articles"].append(row)

    supplements = root.get("부칙", {})
    if kind == "law":
        supplement_rows = entries(supplements.get("부칙단위", []), root_pointer + "/부칙/부칙단위")
    else:
        texts = as_list(supplements.get("부칙내용"))
        dates = as_list(supplements.get("부칙공포일자"))
        numbers = as_list(supplements.get("부칙공포번호"))
        require(len(texts) == len(dates) == len(numbers), slug + ": 부칙 배열 대응 확인 필요")
        supplement_rows = [({"부칙내용": t, "부칙공포일자": d, "부칙공포번호": n},
                            root_pointer + "/부칙/부칙내용" + ("/" + str(i) if isinstance(supplements.get("부칙내용"), list) else ""))
                           for i, (t, d, n) in enumerate(zip(texts, dates, numbers))]
    for i, (unit, pointer) in enumerate(supplement_rows, start=1):
        doc["supplementary_provisions"].append({
            "id": record_id(slug, version, "supplement", unit.get("부칙키", i)),
            "promulgated_date": iso_date(unit.get("부칙공포일자")),
            "promulgation_number": unit.get("부칙공포번호"),
            "text": lines_text(unit.get("부칙내용")),
            "source_json_pointer": pointer,
        })

    for unit, pointer in entries(root.get("별표", {}).get("별표단위", []), root_pointer + "/별표/별표단위"):
        annex_type = str(unit.get("별표구분", ""))
        number = str(unit.get("별표번호", "")).zfill(4)
        branch = str(unit.get("별표가지번호", "00")).zfill(2)
        focus = focus_annexes.get((slug, annex_type, number, branch))
        text = lines_text(unit.get("별표내용"))
        doc["annexes"].append({
            "id": record_id(slug, version, "annex", f"{annex_type}-{number}-{branch}"),
            "title": html.unescape(str(unit.get("별표제목", unit.get("별표제목문자열", "")))),
            "annex_type": annex_type, "number": number, "branch": branch,
            "effective_date": iso_date(unit.get("별표시행일자")),
            "text": text,
            "text_available": bool(text.strip()),
            "project_focus": bool(focus),
            "focus_reason": focus["selection_reason"] if focus else None,
            "attachment_links": links_from_node(unit),
            "attachments_downloaded": False,
            "source_json_pointer": pointer,
        })

    for field, text_field, kind_name in (("개정문", "개정문내용", "amendment_text"),
                                         ("제개정이유", "제개정이유내용", "amendment_reason")):
        if field in root:
            value = root[field].get(text_field, "")
            doc["amendment_materials"].append({"id": record_id(slug, version, kind_name, "1"),
                                               "type": kind_name, "text": lines_text(value),
                                               "source_json_pointer": root_pointer + "/" + field + "/" + text_field})
    count = sum(len(doc[k]) for k in ("articles", "supplementary_provisions", "annexes", "headings", "amendment_materials"))
    require(len(body_record_ids(doc)) == count, slug + ": 레코드 ID 중복")
    return doc, body


def make_relations(base, docs, filename_by_slug):
    original = load_json(base / "prepared/법령연결.json")
    ambiguous = load_json(base / "prepared/법령연결_확인필요.json")
    all_ids = set().union(*(body_record_ids(d) for d in docs))
    by_version = {d["document"]["version_serial"]: d["document"] for d in docs}
    sources, hierarchies = [], []
    for request_path in sorted((base / "relationships").glob("*/*/request.json")):
        request = load_json(request_path)
        raw_path = request_path.with_name("response.json")
        require(hashlib.sha256(raw_path.read_bytes()).hexdigest() == request["sha256"], "관계 API 해시 불일치")
        source_id = request_path.parent.parent.name + ":" + request_path.parent.name
        sources.append({"id": source_id, "fetched_at": request["fetched_at"],
                        "api_guide": request["guide_url"], "source_response_sha256": request["sha256"]})
        if request_path.parent.name == "lsStmdInfoGuide":
            hierarchies.append({"document_id": request_path.parent.parent.name, "api_source_id": source_id,
                                "source_data": load_json(raw_path)})
    grouped = {}
    for edge in original:
        key = tuple(edge.get(k) for k in ("source_slug", "source_article_code", "relationship_type", "target_document",
                                          "target_version_serial", "target_article_number", "target_article_branch"))
        source_id = record_id(edge["source_slug"], edge["source_version"], "article", edge["source_article_code"])
        require(source_id in all_ids, "출발 조문 ID가 자료에 없음")
        if key not in grouped:
            target = by_version.get(str(edge["target_version_serial"]))
            number, branch = str(edge.get("target_article_number") or "0"), str(edge.get("target_article_branch") or "0")
            single_article = number.isdigit() and branch.isdigit()
            target_code = f"{int(number):04d}{int(branch):02d}" if single_article and int(number) else None
            target_id = record_id(target["id"], target["version_serial"], "article", target_code) if target and target_code else None
            status = ("included_article" if target_id in all_ids else
                      "article_range_requires_review" if target and not single_article else
                      "included_document" if target and not target_code else
                      "article_not_found_in_included_version" if target else "document_or_version_not_included")
            grouped[key] = {
                "source_record_id": source_id,
                "source_document": edge["source_document"],
                "source_article": edge["source_article"],
                "relation_type": edge["relationship_type"],
                "target_document": edge["target_document"],
                "target_version_serial": edge["target_version_serial"],
                "target_article": label(target_code) if target_code else None,
                "target_article_number_raw": edge.get("target_article_number"),
                "target_article_branch_raw": edge.get("target_article_branch"),
                "target_reference_status": "single_article_or_document" if single_article else "multiple_article_values",
                "target_article_title": edge["target_article_title"],
                "target_record_id": target_id if target_id in all_ids else None,
                "target_file": filename_by_slug[target["id"]] if target else None,
                "target_availability": status,
                "api_source_id": edge["source_slug"] + ":lsDelegated",
                "occurrences": [],
            }
        grouped[key]["occurrences"].append({"source_location": edge["source_location"],
                                             "link_text": edge["link_text"], "line_text": edge["line_text"],
                                             "source_json_pointer": edge["detail_pointer"]})
    unresolved = []
    for row in ambiguous:
        source_id = record_id(row["source_slug"], row["source_version"], "article", row["source_article_code"])
        require(source_id in all_ids, "확인 필요 관계의 출발 조문이 없음")
        unresolved.append({"source_record_id": source_id, "source_document": row["source_document"],
                           "source_article": row["source_article"], "reason": row["reason"],
                           "api_source_id": row["source_slug"] + ":lsDelegated",
                           "source_json_pointer": row["source_json_pointer"],
                           "source_group": row["source_group"]})
    result = {
        "schema_version": "1.0",
        "purpose": "조문 검색 뒤 위임·인용된 법령을 추가로 찾는 연결 정보. 검색 본문과 별도로 사용.",
        "scope": "기존 선정 조문에 연결된 위임·인용만 정리. 모든 조문의 완전한 관계망이 아님.",
        "meaning": "공식 참조 관계이며 사고별 적용 의무·신고 갈음·면제의 확정 규칙이 아님.",
        "unresolved_policy": "복수 법령과 조문의 대응이 불명확한 API 묶음은 자동 연결하지 않음.",
        "sources": sources,
        "edges": list(grouped.values()),
        "unresolved_groups": unresolved,
        "document_hierarchies": hierarchies,
    }
    require(sum(len(e["occurrences"]) for e in result["edges"]) == len(original), "관계 위치 누락")
    return result


def resolve_pointer(body, pointer):
    value = body
    for component in pointer.strip("/").split("/"):
        component = component.replace("~1", "/").replace("~0", "~")
        value = value[int(component)] if isinstance(value, list) else value[component]
    return value


def validate_texts(docs, source_bodies):
    checked = Counter()
    for doc in docs:
        body = source_bodies[doc["document"]["id"]]
        for section in ("articles", "supplementary_provisions", "annexes", "headings", "amendment_materials"):
            for row in doc[section]:
                node = resolve_pointer(body, row["source_json_pointer"])
                if section in ("articles", "headings"):
                    expected = content_text(node)
                elif section == "supplementary_provisions":
                    expected = lines_text(node["부칙내용"] if isinstance(node, dict) else node)
                elif section == "annexes":
                    expected = lines_text(node.get("별표내용"))
                else:
                    expected = lines_text(node)
                require(row["text"] == expected, row["id"] + ": 원문 텍스트 불일치")
                checked[section] += 1
    return dict(checked)


def write_guide(destination, report):
    guide = f"""Risk 대응 프로젝트 — RAG용 법령 자료

2026-10-30에 법제처 Open API로 수집한 17개 문서를 법령별로 정리했습니다.
기존 API 수집본의 조문·부칙·별표 텍스트를 보존하고 필드 이름과 배열 구조를 통일했습니다.
요약문, 판례, 검토 완료된 의무표, 벡터 DB는 포함하지 않습니다. 기존 수집 로그와 코드도 제외했습니다.

1. 법령별/ : 파일 하나가 법률·시행령·행정규칙 한 개입니다.
   document: 법령명, 출처 URL, 버전, 시행일, 수집일, 수집 목적
   articles: 조문 {report['articles']}개. 각 항목의 text가 한 조 전체이며, 항·호·목을 함께 담습니다.
   supplementary_provisions: 부칙 {report['supplementary_provisions']}개. 개정 시점별 시행일·적용례·경과조치 확인용
   annexes: 별표·서식 {report['annexes']}개. API 제공 텍스트와 첨부 링크
   headings, amendment_materials: 목차 및 개정 배경 확인용. 현행 조문과 구분합니다.

2. 법령연결.json : 검색된 조문이 참조하는 다른 법령을 찾는 보조 자료입니다.
   {report['relationship_edges']}개 연결에 원래 {report['relationship_occurrences']}개 참조 위치를 보존했습니다.
   API에서 대응이 불분명한 {report['unresolved_groups']}개 묶음은 자동 연결하지 않았습니다.
   쉼표로 여러 조 번호를 반환한 연결 {report['multi_article_references']}개도 개별 조문 ID로 임의 연결하지 않았습니다.
   5개 법령체계도도 포함했습니다. 체계도에 이름이 등장해도 본문까지 수집했다는 뜻은 아닙니다.

RAG에 넣는 방법
- 먼저 articles의 각 항목을 검색 문서로 만드세요. JSON 파일 전체를 하나의 검색 문서로 넣지 마세요.
- 본문(text)과 함께 document의 name/source_url/version_serial/effective_date/fetched_at,
  조문의 id/label/title/article_effective_date/project_focus를 검색 메타데이터로 저장하세요.
- 너무 긴 조문은 항·호 단위로 나누되 원래 조문의 id를 남겨, 답변 시 조 전체와 단서도 확인하세요.
- project_focus=true는 기존 조사에서 먼저 살펴볼 조문 98개와 별표·서식 14개에 붙인 표시입니다.
  검색 우선순위 힌트이며, false를 필요 없는 법이나 적용 제외로 해석하면 안 됩니다.
  민법도 기간 계산 때문에 수집했으므로 상속 등 무관한 조문이 우선 검색되지 않도록 범위를 조정하세요.
- 부칙은 조문과 별도 유형으로 검색 가능하게 저장하고, 답변의 근거 법령과 사고 시점에 맞춰 함께 확인하세요.
  문서 시행일만 비교해서 적용 버전을 확정하지 마세요. 과거 사건에는 당시 버전 추가 수집이 필요할 수 있습니다.
- 별표는 text_available=true인 항목만 텍스트 검색에 넣을 수 있습니다.
  표의 행·열 의미를 확인할 때에는 첨부 원문과 대조하세요. PDF/HWP 파일 자체는 내려받지 않았습니다.
- 법령연결은 일반 본문으로 임베딩하지 않고 연결된 조문을 추가 조회하는 데 사용하세요.
  target_record_id=null은 이 묶음에서 정확한 대상 조문을 연결하지 못했다는 뜻입니다.
- source_json_pointer와 source_response_sha256는 원래 API 응답의 위치·검증 정보입니다.
  팀 공유본은 정리된 구조이므로 이 포인터를 현재 파일에 바로 적용하지 마세요. 답변 출처는 source_url을 사용하세요.

남은 데이터 상태
- 별표·서식 {report['annexes_without_text']}개는 텍스트 없이 첨부 링크만 있습니다.
  이 중 우선 확인 대상으로 고른 것은 「과학기술정보통신부 소관 주요정보통신기반시설 보호지침」의
  「침해사고 대응 및 복구 조치시 고려사항(제19조 및 제21조 관련)」 1개입니다.
- 이번 정리는 데이터 전달 형식까지입니다. 임베딩·검색 품질 평가와 사고별 법 적용 검토는 별도 작업입니다.
"""
    (destination / "먼저읽기.txt").write_text(guide, encoding="utf-8")


def package(source, output):
    source, output = Path(source).resolve(), Path(output).resolve()
    require(not output.exists(), "기존 폴더를 덮어쓰지 않습니다: " + str(output))
    archive = output.with_suffix(".zip")
    require(not archive.exists(), "기존 ZIP을 덮어쓰지 않습니다: " + str(archive))
    manifest = load_json(source / "manifest.json")
    require(manifest.get("status") == "downloaded", "수집 완료 상태가 아님")
    scope = {d["slug"]: d for d in load_json(ROOT / "project_scope.json")["documents"]}
    focus_rows = [json.loads(line) for line in (source / "prepared/조문_검색후보.jsonl").read_text(encoding="utf-8").splitlines() if line]
    focus_articles = {(a["document_slug"], a["article_code"]): a for a in focus_rows}
    annex_rows = load_json(source / "prepared/선정_별표서식.json")
    focus_annexes = {(a["document_slug"], a["type"], a["number"], a["branch"]): a for a in annex_rows}
    docs, source_bodies, filenames = [], {}, {}
    for item in manifest["documents"]:
        require(item.get("status") == "downloaded", "미완료 문서 포함")
        doc, body = make_document(item, source, scope[item["slug"]], focus_articles, focus_annexes)
        docs.append(doc)
        source_bodies[item["slug"]] = body
        filenames[item["slug"]] = "법령별/" + re.sub(r'[\\/:*?"<>|]', "_", item["name"]) + ".json"
    relations = make_relations(source, docs, filenames)
    report = {"created_at": datetime.now(KST).isoformat(), "documents": len(docs),
              **validate_texts(docs, source_bodies),
              "focus_articles": sum(r["project_focus"] for d in docs for r in d["articles"]),
              "focus_annexes": sum(r["project_focus"] for d in docs for r in d["annexes"]),
              "annexes_without_text": sum(not r["text_available"] for d in docs for r in d["annexes"]),
              "relationship_edges": len(relations["edges"]),
              "relationship_occurrences": sum(len(r["occurrences"]) for r in relations["edges"]),
              "unresolved_groups": len(relations["unresolved_groups"]),
              "multi_article_references": sum(r["target_reference_status"] == "multiple_article_values" for r in relations["edges"]),
              "source_text_comparison": "passed",
              "source_hash_verification": "passed"}
    require(report["focus_articles"] == len(focus_rows) and report["focus_annexes"] == len(annex_rows), "우선 확인 표시 누락")
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="rag-share-", dir=output.parent) as temp:
        staged = Path(temp) / output.name
        staged.mkdir()
        for doc in docs:
            save_json(staged / filenames[doc["document"]["id"]], doc)
        save_json(staged / "법령연결.json", relations)
        write_guide(staged, report)
        oc = load_oc()
        files = sorted(p for p in staged.rglob("*") if p.is_file())
        for path in files:
            data = path.read_bytes()
            require(not oc or oc.encode() not in data, "공유 파일 인증값 검사 실패")
            require(b"/Users/" not in data, "공유 파일에 로컬 경로 포함")
            if path.suffix == ".json":
                json.loads(data)
        require(len(files) == len(docs) + 2, "예상하지 않은 공유 파일 포함")
        with zipfile.ZipFile(archive, "w", zipfile.ZIP_DEFLATED) as z:
            for path in files:
                z.write(path, str(path.relative_to(staged.parent)))
        with zipfile.ZipFile(archive) as z:
            require(z.testzip() is None, "ZIP 검증 실패")
        staged.rename(output)
    report.update({"files": len(files), "credential_check": "passed", "archive": str(archive), "bytes": archive.stat().st_size})
    save_json(output.parent / (output.name + "_검증.json"), report)
    return report


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        print(json.dumps(package(args.input, args.output), ensure_ascii=False, indent=2))
    except (ApiError, OSError, ValueError) as error:
        parser.exit(2, str(error) + "\n")
