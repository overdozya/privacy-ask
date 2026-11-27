#!/usr/bin/env python3
"""성공적으로 수신한 실제 법령 JSON을 검증하고 공유용 ZIP으로 묶는다."""
import argparse
import hashlib
import json
import re
import shutil
import tempfile
import zipfile
from datetime import datetime
from pathlib import Path

from law_api import ApiError, KST, ROOT, load_json, load_oc, save_json, validate_body


def share_paths(value):
    if isinstance(value, list):
        return [share_paths(v) for v in value]
    if isinstance(value, dict):
        return {k: "수집기록/" + v if k == "source_file" and isinstance(v, str) else share_paths(v)
                for k, v in value.items()}
    return value


def package(source, output):
    source, output = Path(source).resolve(), Path(output).resolve()
    manifest = load_json(source / "manifest.json")
    if manifest.get("status") != "downloaded" or not manifest.get("documents"):
        raise ApiError("수집이 완료되지 않았습니다. 인증 실패·누락을 해결한 뒤 공유 파일을 만드세요.")
    if output.exists():
        raise ApiError("같은 이름의 공유 파일이 이미 있습니다. 다른 이름을 지정하세요.")
    collected = []
    scope = {d["slug"]: d for d in load_json(ROOT / "project_scope.json")["documents"]}
    with tempfile.TemporaryDirectory(prefix="law-json-share-") as temp:
        root = Path(temp) / "법제처_JSON_수집자료"
        for item in manifest["documents"]:
            slug = item.get("slug", "")
            if item.get("status") != "downloaded" or not re.fullmatch(r"[a-z0-9-]+", slug):
                raise ApiError("수집 목록에 미완료 또는 잘못된 문서 경로가 있습니다.")
            base = source / slug
            body_path = base / "body/response.json"
            raw = body_path.read_bytes()
            metadata = load_json(base / "body/request.json")
            selected = load_json(base / "selected.json")
            if metadata.get("synthetic_test_fixture") or metadata.get("http_status") != 200:
                raise ApiError("실제 성공 응답 기록이 없는 문서가 있습니다: " + item["name"])
            digest = hashlib.sha256(raw).hexdigest()
            if digest != metadata.get("sha256"):
                raise ApiError("수집 이후 원문 파일이 바뀌었습니다: " + item["name"])
            data = json.loads(raw.decode("utf-8-sig"))
            validate_body(data, selected, item["kind"])
            category = "법령" if item["kind"] == "law" else "행정규칙"
            filename = re.sub(r'[\\/:*?"<>|]', "_", item["name"]) + ".json"
            destination = root / "원본_JSON" / category / filename
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_bytes(raw)
            # 검색 응답·선택 버전·요청 정보도 보존하되 인증 설정은 포함하지 않는다.
            provenance = root / "수집기록" / slug
            shutil.copytree(base, provenance)
            collected.append({
                "name": item["name"], "kind": item["kind"], "group": item["group"],
                "collection_reason": scope[item["slug"]]["collection_reason"],
                "downstream_uses": scope[item["slug"]].get("downstream_uses", []),
                "selected_version": selected, "request": metadata,
                "original_file": str(destination.relative_to(root)),
                "sha256": digest, "response": data,
            })
        save_json(root / "법령_통합.json", {
            "packaged_at": datetime.now(KST).isoformat(), "document_count": len(collected),
            "description": "법제처 API 실제 수신 JSON. 원문 내용은 보존하고 링크에 반영된 OC만 가림. 의무·기한 판단 결과와 구분.",
            "documents": collected,
        })
        save_json(root / "수집목록.json", {
            "source": "국가법령정보 공동활용 Open API", "document_count": len(collected),
            "documents": [{k: v for k, v in item.items() if k != "response"} for item in collected],
        })
        if (source / "relationships").exists():
            shutil.copytree(source / "relationships", root / "수집기록/relationships")
        if (source / "prepared").exists():
            derived = root / "가공_JSON"
            derived.mkdir()
            for path in (source / "prepared").iterdir():
                if path.suffix == ".json":
                    save_json(derived / path.name, share_paths(load_json(path)))
                elif path.suffix == ".jsonl":
                    rows = [share_paths(json.loads(line)) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]
                    (derived / path.name).write_text("".join(json.dumps(row, ensure_ascii=False) + "\n" for row in rows), encoding="utf-8")
        shutil.copy2(ROOT / "수집결과_읽는법.txt", root / "수집결과_읽는법.txt")
        code = root / "요청예제_수집코드"
        code.mkdir()
        for name in ("README.md", "law_api.py", "collect_related.py", "prepare_dataset.py", "package_data.py", "test_law_api.py", "api_catalog.json", "project_scope.json", "collection_policy.json", "all_api_index.json", "법제처_JSON_API.postman_collection.json", ".env.example", "법제처_API_조사결과.txt"):
            shutil.copy2(ROOT / name, code / name)
        shutil.copytree(ROOT / "samples", code / "samples")
        (root / "먼저읽기.txt").write_text(
            f"법제처 Open API로 실제 수신한 {len(collected)}개 문서의 JSON 자료입니다.\n\n"
            "원본_JSON: API 본문 내용은 보존하고, 상세 링크에 반영된 OC만 가렸습니다.\n"
            "법령_통합.json: 같은 본문들을 출처·버전 정보와 함께 하나로 묶었습니다.\n"
            "수집목록.json: 문서명·수집시각·요청 정보·원문 해시입니다.\n"
            "수집기록: 검색 결과, 선택한 버전, 본문 응답 및 요청 정보입니다.\n"
            "가공_JSON: 목적에 따라 고른 조문·서식 후보, 위임·인용 관계와 확인 필요 항목입니다.\n"
            "요청예제_수집코드: 재수집 방법과 JSON 요청 예제입니다.\n\n"
            "원문의 별표·첨부 링크에 있는 PDF/HWP 파일 자체는 이 JSON에 포함되지 않습니다.\n"
            "판례·결정례 본문은 이번 묶음에서 수집하지 않았습니다. 구체적 쟁점별 선별 기준을 포함했습니다.\n"
            "검색 후보 조문과 개별 사고의 법 적용 판단은 구분해야 합니다. 과거 사건은 해당 시점의 버전과 부칙을 확인하세요.\n"
            "API 인증값은 이 공유 자료에 넣지 않았습니다.\n",
            encoding="utf-8",
        )
        oc = load_oc()
        if oc:
            leaked = [str(p.relative_to(root)) for p in root.rglob("*") if p.is_file() and oc.encode() in p.read_bytes()]
            if leaked:
                raise ApiError("공유 파일에서 인증값 포함 여부를 추가 확인해야 합니다: " + ", ".join(leaked))
        output.parent.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            for path in sorted(root.rglob("*")):
                if path.is_file():
                    archive.write(path, path.relative_to(root.parent))
    return {"archive": str(output), "document_count": len(collected), "bytes": output.stat().st_size}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, required=True, help="collect로 생성한 실행 폴더")
    parser.add_argument("--output", type=Path, required=True, help="새로 만들 공유용 ZIP 경로")
    args = parser.parse_args()
    try:
        print(json.dumps(package(args.input, args.output), ensure_ascii=False, indent=2))
    except (ApiError, OSError, ValueError) as exc:
        parser.exit(2, str(exc) + "\n")
