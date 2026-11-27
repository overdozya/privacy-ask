#!/usr/bin/env python3
"""법제처 JSON 요청 예제 및 프로젝트 법령 수집기. Python 3.10+, 외부 패키지 없음."""
from __future__ import annotations

import argparse
import getpass
import hashlib
import json
import os
import re
import sys
import time
import unicodedata
from datetime import datetime, timedelta, timezone
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parent
BASE = "https://www.law.go.kr/DRF/"
KST = timezone(timedelta(hours=9))


class ApiError(Exception):
    pass


class AuthenticationError(ApiError):
    pass


class ServicePermissionError(ApiError):
    pass


def load_json(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def load_oc(path=None):
    """환경변수 우선, 그다음 로컬 .env. 인증값을 출력하거나 결과에 저장하지 않는다."""
    if os.environ.get("LAW_API_OC", "").strip():
        return os.environ["LAW_API_OC"].strip()
    path = Path(path) if path else ROOT / ".env"
    if path.exists():
        for line in path.read_text(encoding="utf-8-sig").splitlines():
            key, separator, value = line.strip().removeprefix("export ").partition("=")
            if separator and key.strip() == "LAW_API_OC":
                return value.strip().strip("\"'")
    return ""


def save_json(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def walk_dicts(value):
    if isinstance(value, dict):
        yield value
        for child in value.values():
            yield from walk_dicts(child)
    elif isinstance(value, list):
        for child in value:
            yield from walk_dicts(child)


def normalized_title(value):
    return re.sub(r"\s+", "", unicodedata.normalize("NFC", str(value)))


def article_code(value):
    """34 / 48-3 / 제48조의3 → 003400 / 004803."""
    value = str(value).strip()
    if re.fullmatch(r"\d{6}", value):
        if int(value[:4]) == 0:
            raise ValueError("조 번호는 1 이상이어야 합니다.")
        return value
    match = re.fullmatch(r"(?:제)?(\d{1,4})(?:조)?(?:(?:의|-)(\d{1,2}))?", value)
    if not match or int(match[1]) == 0:
        raise ValueError("조 번호는 34, 48-3, 제48조의3 형태로 입력하세요.")
    return f"{int(match[1]):04d}{int(match[2] or 0):02d}"


def check_api_error(data):
    # 법령 본문 안의 '실패', '오류'라는 표현과 API 오류 봉투를 혼동하지 않는다.
    for node in walk_dicts(data):
        messages = " ".join(str(node[k]) for k in ("result", "msg", "message", "resultMsg", "errorMsg", "error")
                            if k in node and not isinstance(node[k], (dict, list)))
        if not messages:
            continue
        if any(s in messages for s in ("사용자 정보 검증", "사용자 검증", "인증에 실패", "인증키 오류")):
            raise AuthenticationError("사용자 인증 실패: 등록한 OC와 호출 서버의 IP·도메인을 확인하세요.")
        if re.search(r"실패|오류|잘못|권한|제한|error|invalid|denied|failed", messages, re.I):
            raise ApiError("API가 오류 메시지를 반환했습니다. 신청 권한·파라미터·이용 제한을 확인하세요.")


def decode_response(raw):
    if "미신청된 목록/본문" in raw.decode("utf-8", errors="replace"):
        raise ServicePermissionError("미신청된 목록/본문에 대한 접근입니다. 등록한 API의 해당 목록 JSON·본문 JSON 신청 상태를 확인하세요.")
    try:
        data = json.loads(raw.decode("utf-8-sig"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise ApiError("JSON 응답이 아닙니다. 해당 API의 JSON 지원과 인증 상태를 확인하세요.") from exc
    check_api_error(data)
    if not isinstance(data, (dict, list)) or not data:
        raise ApiError("비어 있거나 예상과 다른 JSON 응답입니다.")
    return data


def redact_credentials(value):
    """응답의 상세 링크에도 OC가 들어올 수 있으므로 공유 전 인증값만 가린다."""
    if isinstance(value, dict):
        return {k: "[REDACTED]" if k.lower() == "oc" else redact_credentials(v) for k, v in value.items()}
    if isinstance(value, list):
        return [redact_credentials(v) for v in value]
    if isinstance(value, str):
        return re.sub(r"([?&](?:amp;)?OC=)[^&#\s\"<>]*", r"\1REDACTED", value, flags=re.I)
    return value


def prepare_request(api, params):
    if not api["json_supported"]:
        raise ApiError(f"{api['title']}: 공식 가이드에서 JSON을 지원하지 않습니다.")
    params = {str(k): str(v) for k, v in params.items()}
    if any(k in params for k in ("OC", "target", "type")):
        raise ApiError("OC는 환경변수/비공개 입력을 사용하세요. target과 type은 자동 설정됩니다.")
    known = {row["name"] for row in api["request_parameters"]}
    unknown = set(params) - known
    if unknown:
        raise ApiError("가이드에 없는 요청변수: " + ", ".join(sorted(unknown)))
    for row in api["request_parameters"]:
        key = row["name"]
        if row["required"] and key not in ("OC", "target", "type") and not params.get(key):
            if key == "efYd" and params.get("ID"):
                continue
            raise ApiError(f"필수 요청변수가 없습니다: {key}")
    if api["target"] in ("eflaw", "eflawjosub", "law", "lsStmd", "lsDelegated", "thdCmp", "oldAndNew") and api["endpoint"] == "lawService.do":
        if not (params.get("ID") or params.get("MST")):
            raise ApiError("본문/관계 조회에는 검색 결과의 ID 또는 MST가 필요합니다.")
        if params.get("ID") and params.get("MST"):
            raise ApiError("조회 기준을 명확히 하기 위해 ID와 MST 중 하나만 입력하세요.")
    if api["target"] == "admrul" and api["endpoint"] == "lawService.do":
        if not any(params.get(k) for k in ("ID", "LID", "LM")):
            raise ApiError("행정규칙 본문은 ID(일련번호), LID 또는 LM이 필요합니다.")
    if api["target"] == "lstrmRlt" and not (params.get("query") or params.get("MST")):
        raise ApiError("용어 관계 조회에는 query 또는 MST가 필요합니다.")
    if "JO" in params and api["target"] != "prec":
        params["JO"] = article_code(params["JO"])
    if "display" in params:
        try:
            size = int(params["display"])
        except ValueError as exc:
            raise ApiError("display는 정수여야 합니다.") from exc
        row = next(r for r in api["request_parameters"] if r["name"] == "display")
        if size < 1 or ("max=100" in row["description"] and size > 100):
            raise ApiError("display가 공식 가이드의 허용 범위를 벗어났습니다.")
    if "page" in params and (not params["page"].isdigit() or int(params["page"]) < 1):
        raise ApiError("page는 1 이상의 정수여야 합니다.")
    if api["target"] in ("eflaw", "eflawjosub") and api["endpoint"] == "lawService.do" and params.get("MST"):
        if not re.fullmatch(r"\d{8}", params.get("efYd", "")):
            raise ApiError("MST로 조회할 때 efYd에는 검색 결과의 시행일자 8자리를 넣으세요.")
    return {"target": api["target"], "type": "JSON", **params}


class Client:
    def __init__(self, oc, interval=0.5, timeout=30):
        if not oc.strip():
            raise ApiError("등록한 API 인증값(OC)이 필요합니다.")
        self.oc, self.interval, self.timeout = oc.strip(), max(interval, 0), timeout
        self.last_request = 0.0

    def fetch(self, api, params):
        safe_params = prepare_request(api, params)
        time.sleep(max(0, self.interval - (time.monotonic() - self.last_request)))
        self.last_request = time.monotonic()
        url = BASE + api["endpoint"] + "?" + urlencode({"OC": self.oc, **safe_params})
        try:
            with urlopen(Request(url, headers={"Accept": "application/json", "User-Agent": "RiskLawResearch/1.0"}), timeout=self.timeout) as response:
                raw = response.read()
                status = response.status
                content_type = response.headers.get("Content-Type", "")
        except HTTPError as exc:
            # 예외 원문에는 OC가 들어간 URL이 포함될 수 있으므로 출력하지 않는다.
            if exc.code in (401, 403):
                raise AuthenticationError(f"HTTP {exc.code}: API 신청 및 OC/IP 등록을 확인하세요.") from None
            raise ApiError(f"HTTP {exc.code}: 요청 실패. 자동 재시도하지 않았습니다.") from None
        except (URLError, TimeoutError, OSError):
            raise ApiError("네트워크 요청 실패: 연결 상태와 법제처 서비스 상태를 확인하세요.") from None
        data = decode_response(raw)
        upstream_digest = hashlib.sha256(raw).hexdigest()
        cleaned = redact_credentials(data)
        redacted = cleaned != data
        if redacted:
            data = cleaned
            raw = (json.dumps(data, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
        metadata = {
            "fetched_at": datetime.now(KST).isoformat(), "guide_url": api["guide_url"],
            "endpoint": BASE + api["endpoint"], "params": {"OC": "[REDACTED]", **safe_params},
            "http_status": status, "content_type": content_type,
            "sha256": hashlib.sha256(raw).hexdigest(), "bytes": len(raw),
            "upstream_sha256": upstream_digest,
            "credential_links_redacted": redacted,
        }
        return data, raw, metadata


def fetch_and_save(client, api, params, directory):
    data, raw, metadata = client.fetch(api, params)
    directory = Path(directory)
    directory.mkdir(parents=True, exist_ok=True)
    (directory / "response.json").write_bytes(raw)
    save_json(directory / "request.json", metadata)
    return data


def record_keys(kind):
    if kind == "law":
        return "법령명한글", "법령일련번호"
    if kind == "admrul":
        return "행정규칙명", "행정규칙일련번호"
    raise ValueError("지원하지 않는 문서 종류: " + str(kind))


def search_records(data, kind):
    title_key, serial_key = record_keys(kind)
    records = []
    seen = set()
    for node in walk_dicts(data):
        if title_key in node and serial_key in node:
            key = (str(node[serial_key]), str(node.get("시행일자", "")))
            if key not in seen:
                seen.add(key)
                records.append(node)
    return records


def total_count(data):
    for node in walk_dicts(data):
        if "totalCnt" in node:
            try:
                return int(node["totalCnt"])
            except (TypeError, ValueError) as exc:
                raise ApiError("totalCnt 형식을 확인해야 합니다.") from exc
    raise ApiError("검색 응답에 totalCnt가 없습니다. 실제 JSON 구조를 확인하세요.")


def search_all(client, api, name, kind, directory, max_pages=100):
    result, seen_pages = [], set()
    page_size = 100
    for page in range(1, max_pages + 1):
        data = fetch_and_save(client, api, {
            "query": name, "search": 1, "nw": 3 if kind == "law" else 1,
            "display": page_size, "page": page,
        }, Path(directory) / f"page-{page:03d}")
        count = total_count(data)
        records = search_records(data, kind)
        if count == 0:
            return []
        if not records:
            raise ApiError("검색 건수는 있으나 레코드를 읽지 못했습니다. 저장된 응답을 확인하세요.")
        fingerprint = json.dumps(records, ensure_ascii=False, sort_keys=True)
        if fingerprint in seen_pages:
            raise ApiError("검색 API가 같은 페이지를 반복했습니다. 수집을 중단합니다.")
        seen_pages.add(fingerprint)
        result.extend(records)
        if page * page_size >= count:
            if len(result) < count:
                raise ApiError("검색 건수보다 수신한 레코드가 적습니다. 부분 결과를 선택하지 않습니다.")
            return result
    raise ApiError("최대 검색 페이지를 넘었습니다. 검색 조건을 좁혀주세요.")


def choose_current(records, name, kind, today=None):
    today = today or datetime.now(KST).strftime("%Y%m%d")
    title_key, serial_key = record_keys(kind)
    matches = {}
    for row in records:
        if normalized_title(row.get(title_key, "")) != normalized_title(name):
            continue
        effective = re.sub(r"\D", "", str(row.get("시행일자", "")))
        if not re.fullmatch(r"\d{8}", effective):
            raise ApiError(f"{name}: 시행일자가 불명확하여 자동 선택하지 않았습니다.")
        if effective <= today:
            matches[(str(row[serial_key]), effective)] = row
    if not matches:
        raise ApiError(f"{name}: 이름과 시행일을 확인할 수 있는 현행 버전이 없습니다.")
    if len(matches) != 1:
        raise ApiError(f"{name}: 현행 후보 {len(matches)}개. 검색 응답에서 버전을 확인해야 합니다.")
    return next(iter(matches.values()))


def body_params(row, kind):
    if kind == "law":
        return {"MST": str(row["법령일련번호"]), "efYd": re.sub(r"\D", "", str(row["시행일자"]))}
    return {"ID": str(row["행정규칙일련번호"])}


def validate_body(data, row, kind):
    names = ("법령명_한글", "법령명한글") if kind == "law" else ("행정규칙명",)
    expected = normalized_title(row[record_keys(kind)[0]])
    returned = [normalized_title(node[key]) for node in walk_dicts(data) for key in names if key in node]
    has_body = any(node.get(key) for node in walk_dicts(data) for key in ("조문내용", "부칙내용"))
    if expected not in returned or not has_body:
        raise ApiError("요청한 문서의 이름과 본문을 검증하지 못했습니다. 저장된 JSON을 확인하세요.")


def collect(client, catalog, documents, output):
    summary = {"started_at": datetime.now(KST).isoformat(), "status": "in_progress", "documents": []}
    output = Path(output)
    save_json(output / "manifest.json", summary)
    for doc in documents:
        kind = doc["kind"]
        search_id, body_id = (("lsEfYdListGuide", "lsEfYdInfoGuide") if kind == "law"
                              else ("admrulListGuide", "admrulInfoGuide"))
        directory = output / doc["slug"]
        item = {"slug": doc["slug"], "name": doc["name"], "kind": kind, "group": doc["group"], "status": "in_progress",
                "collection_reason": doc.get("collection_reason", ""),
                "downstream_uses": doc.get("downstream_uses", []),
                "article_focus": doc.get("article_focus", [])}
        summary["documents"].append(item)
        try:
            rows = search_all(client, catalog[search_id], doc["name"], kind, directory / "search")
            chosen = choose_current(rows, doc["name"], kind)
            save_json(directory / "selected.json", chosen)
            params = body_params(chosen, kind)
            data = fetch_and_save(client, catalog[body_id], params, directory / "body")
            validate_body(data, chosen, kind)
            item.update(status="downloaded", selection=params, response=str((directory / "body/response.json").resolve()))
            print("받음: " + doc["name"])
        except (AuthenticationError, ServicePermissionError) as exc:
            failure_status = "permission_missing" if isinstance(exc, ServicePermissionError) else "authentication_failed"
            item.update(status=failure_status, error=str(exc))
            summary["status"] = failure_status
            save_json(output / "manifest.json", summary)
            raise
        except (ApiError, ValueError) as exc:
            item.update(status="needs_review", error=str(exc))
            print("확인 필요: " + str(exc), file=sys.stderr)
        save_json(output / "manifest.json", summary)
    summary["finished_at"] = datetime.now(KST).isoformat()
    summary["status"] = "downloaded" if all(x["status"] == "downloaded" for x in summary["documents"]) else "partial"
    save_json(output / "manifest.json", summary)
    return summary


def main(argv=None):
    catalog = {a["guide_id"]: a for a in load_json(ROOT / "api_catalog.json")["apis"]}
    scope = load_json(ROOT / "project_scope.json")["documents"]
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    plan = commands.add_parser("plan", help="인증/통신 없이 수집 대상과 요청 순서를 출력")
    download = commands.add_parser("collect", help="이름 검색 → 버전 확인 → 현행 본문 JSON 저장")
    groups = sorted({d["group"] for d in scope})
    for sub in (plan, download):
        sub.add_argument("--profile", choices=["initial", "candidates"], default="initial", help="기본 initial: 우선 수집 17개. candidates: 확장 후보까지 25개")
        sub.add_argument("--group", choices=groups + ["all"], action="append", help="수집 범위 안에서 그룹을 좁힐 때 지정")
        sub.add_argument("--slug", help="범위 목록에서 이 slug 한 문서만 선택")
    download.add_argument("--output", type=Path)
    request = commands.add_parser("request", help="카탈로그 API 한 건 호출; 검색은 지정 페이지 한 장만 저장")
    request.add_argument("--api", choices=sorted(catalog), required=True)
    request.add_argument("--param", action="append", default=[], metavar="KEY=VALUE")
    request.add_argument("--output", type=Path)
    request.add_argument("--dry-run", action="store_true")
    args = parser.parse_args(argv)
    docs = []
    if args.command in ("plan", "collect"):
        selected_groups = args.group or ["all"]
        docs = [d for d in scope if (d["slug"] == args.slug if args.slug else
                (args.profile == "candidates" or d.get("collection_stage", "initial") == "initial")
                and ("all" in selected_groups or d["group"] in selected_groups))]
        if not docs:
            raise ApiError("선택한 수집 대상이 없습니다.")
    if args.command == "plan":
        print(json.dumps({"network_requests": 0, "documents": [{
            **doc, "search": {"target": "eflaw" if doc["kind"] == "law" else "admrul",
            "type": "JSON", "query": doc["name"], "nw": 3 if doc["kind"] == "law" else 1},
            "body_selection": "검색 결과의 법령일련번호→MST 및 시행일자→efYd" if doc["kind"] == "law" else "검색 결과의 행정규칙일련번호→ID",
        } for doc in docs]}, ensure_ascii=False, indent=2))
        return 0
    if args.command == "request":
        params = {}
        for raw in args.param:
            if "=" not in raw:
                raise ApiError("--param은 KEY=VALUE 형식으로 입력하세요.")
            key, value = raw.split("=", 1)
            if key in params:
                raise ApiError("중복 요청변수: " + key)
            params[key] = value
        api = catalog[args.api]
        safe_params = prepare_request(api, params)
        if args.dry_run:
            print(json.dumps({"endpoint": BASE + api["endpoint"], "params": {"OC": "[REDACTED]", **safe_params}}, ensure_ascii=False, indent=2))
            return 0
    oc = load_oc() or getpass.getpass("등록한 법제처 API 인증값(OC): ")
    client = Client(oc)
    stamp = datetime.now(KST).strftime("%Y%m%d-%H%M%S-%f")
    destination = args.output or ROOT / "downloads" / stamp
    if destination.exists() and any(destination.iterdir()):
        raise ApiError("출력 폴더가 비어 있지 않습니다. 기존 수집본을 보존하도록 새 경로를 지정하세요.")
    if args.command == "request":
        fetch_and_save(client, api, params, destination)
        print("JSON 저장: " + str(destination.resolve()))
        return 0
    summary = collect(client, catalog, docs, destination)
    print("수집 기록: " + str((destination / "manifest.json").resolve()))
    return 0 if summary["status"] == "downloaded" else 2


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (ApiError, ValueError) as exc:
        print(str(exc), file=sys.stderr)
        sys.exit(2)
    except (KeyboardInterrupt, EOFError):
        print("입력이 중단되었습니다.", file=sys.stderr)
        sys.exit(130)
