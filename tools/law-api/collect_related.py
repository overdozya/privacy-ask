#!/usr/bin/env python3
"""수집한 핵심 문서의 체계도·위임 관계만 조회한다. 링크를 재귀 수집하지 않는다."""
import argparse
from pathlib import Path

from law_api import (ROOT, ApiError, AuthenticationError, Client, ServicePermissionError,
                     fetch_and_save, load_json, load_oc, save_json)


def run(source):
    source = Path(source)
    manifest = load_json(source / "manifest.json")
    if manifest.get("status") != "downloaded":
        raise ApiError("먼저 선택한 문서들의 본문 수집을 완료하세요.")
    catalog = {a["guide_id"]: a for a in load_json(ROOT / "api_catalog.json")["apis"]}
    client = Client(load_oc())
    root = source / "relationships"
    if root.exists():
        raise ApiError("관계 수집 폴더가 이미 있습니다. 기존 결과를 먼저 확인하세요.")
    results, unavailable = [], set()
    for doc in manifest["documents"]:
        if doc["kind"] != "law" or doc["group"] not in ("core", "conditional"):
            continue
        guides = ["lsDelegated"]
        if doc["slug"].endswith("-law"):
            guides.insert(0, "lsStmdInfoGuide")
        for gid in guides:
            item = {"slug": doc["slug"], "name": doc["name"], "api": gid,
                    "source_mst": doc["selection"]["MST"],
                    "purpose": "조문이 연결한 세부 규정 및 근거 위치 확인; 연결된 모든 문서를 자동 수집하지 않음"}
            results.append(item)
            if gid in unavailable:
                item.update(status="permission_missing", error="같은 API의 권한 오류가 확인되어 반복 호출하지 않음")
                continue
            try:
                fetch_and_save(client, catalog[gid], {"MST": doc["selection"]["MST"]}, root / doc["slug"] / gid)
                item.update(status="received", path=str((root / doc["slug"] / gid / "response.json").relative_to(source)))
                print("관계 받음:", doc["name"], gid, flush=True)
            except ServicePermissionError as exc:
                unavailable.add(gid)
                item.update(status="permission_missing", error=str(exc))
            except AuthenticationError:
                save_json(root / "manifest.json", {"status": "authentication_failed", "requests": results})
                raise
            except ApiError as exc:
                item.update(status="needs_review", error=str(exc))
            save_json(root / "manifest.json", {"status": "in_progress", "requests": results})
    report = {"status": "received" if all(x["status"] == "received" for x in results) else "partial", "requests": results}
    save_json(root / "manifest.json", report)
    return report


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, required=True)
    args = parser.parse_args()
    try:
        result = run(args.input)
        print("관계 조회:", sum(x["status"] == "received" for x in result["requests"]), "/", len(result["requests"]))
    except ApiError as exc:
        parser.exit(2, str(exc) + "\n")
