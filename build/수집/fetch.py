#!/usr/bin/env python3
"""법제처 Open API에서 대상 25종을 받는다.

- 도구 폴더(`tools/law-api`)의 `law_api.py` Client를 그대로 빌려 쓴다.
  인증값(OC)은 그 도구가 자기 `.env`에서 읽고, 응답 속 OC 링크는 도구가 [REDACTED]로 가린다.
  이 스크립트는 OC를 읽지도 출력하지도 않는다.
- 받은 응답은 `data/원천/<날짜>/` 아래에 도구가 받은 바이트 그대로 저장한다(고치지 않음).

명령
  search  : 25종 이름 검색 → data/원천/<날짜>/검색/ 저장, 후보 대조표 출력 (본문은 받지 않음)
  bodies  : 승인된 대조표(data/수집/선정_대조표.json)대로 본문·위임·별표목록 받기
"""
from __future__ import annotations

import argparse
import json
import re
import sys
import unicodedata
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))  # build/: paths.py
import paths as P  # noqa: E402
TOOL = P.LAW_API
sys.path.insert(0, str(TOOL))
import law_api  # noqa: E402  (도구 폴더는 고치지 않고 import만 한다)

TARGETS = P.TARGETS
SELECTION = P.SELECTION


def norm(s):
    return re.sub(r"\s+", "", unicodedata.normalize("NFC", str(s)))


def catalog():
    return {a["guide_id"]: a for a in law_api.load_json(TOOL / "api_catalog.json")["apis"]}


def client():
    oc = law_api.load_oc()  # 도구 .env에서 읽음. 값은 이 프로세스 밖으로 나가지 않는다.
    if not oc:
        raise SystemExit("도구 폴더 .env에 LAW_API_OC가 없습니다.")
    return law_api.Client(oc, interval=0.6)


def save(directory: Path, raw: bytes, meta: dict):
    directory.mkdir(parents=True, exist_ok=True)
    (directory / "response.json").write_bytes(raw)
    law_api.save_json(directory / "request.json", meta)


def get(cl, api, params, directory: Path):
    data, raw, meta = cl.fetch(api, params)
    save(directory, raw, meta)
    return data


def records(data, title_key):
    return [n for n in law_api.walk_dicts(data) if title_key in n]


def cmd_search(args):
    cat, cl = catalog(), client()
    docs = json.loads(TARGETS.read_text())["documents"]
    base = P.SOURCE / args.date / "검색"
    table = []
    for d in docs:
        tag = f"doc{d['doc_id']:02d}"
        if d["kind"] == "law":
            api = cat["lsEfYdListGuide"]
            # 예정+현행(nw=2,3). 연혁은 보지 않는다.
            data = get(cl, api, {"query": d["title"], "search": 1, "nw": "2,3", "display": 100, "page": 1},
                       base / tag)
            rows = records(data, "법령명한글")
            hits = [r for r in rows if norm(r.get("법령명한글")) == norm(d["title"])]
            cand = [{k: r.get(k) for k in ("법령명한글", "법령약칭명", "법령ID", "법령일련번호", "공포일자", "공포번호",
                                            "시행일자", "현행연혁코드", "제개정구분명", "법령구분명")} for r in hits]
        else:
            api = cat["admrulListGuide"]
            data = get(cl, api, {"query": d["title"], "search": 1, "nw": 1, "display": 100, "page": 1},
                       base / tag)
            rows = records(data, "행정규칙명")
            hits = [r for r in rows if norm(r.get("행정규칙명")) == norm(d["title"])]
            cand = [{k: r.get(k) for k in ("행정규칙명", "행정규칙ID", "행정규칙일련번호", "발령일자", "발령번호",
                                            "시행일자", "현행연혁구분", "행정규칙종류", "소관부처명")} for r in hits]
        table.append({"doc_id": d["doc_id"], "title": d["title"], "kind": d["kind"],
                      "search_total": len(rows), "exact_name_hits": cand})
        print(f"{tag} {d['title']}: 검색 {len(rows)}건, 이름 일치 {len(cand)}건", flush=True)
    law_api.save_json(P.SOURCE / args.date / "검색_후보표.json", table)


def annex_list(cl, api, name, directory: Path):
    """별표·서식 목록: 해당법령검색(search=2). 여러 쪽이면 모두 받는다."""
    page = 1
    while True:
        data = get(cl, api, {"query": name, "search": 2, "display": 100, "page": page}, directory / f"page-{page:03d}")
        try:
            total = law_api.total_count(data)
        except law_api.ApiError:
            return
        if page * 100 >= total:
            return
        page += 1


def cmd_bodies(args):
    cat, cl = catalog(), client()
    sel = json.loads(SELECTION.read_text())["documents"]
    base = P.SOURCE / args.date
    log = []
    for d in sel:
        tag = f"doc{d['doc_id']:02d}"
        if args.only and d["doc_id"] not in args.only:
            continue
        jobs = []
        if d["kind"] == "law":
            c = d["current"]
            jobs.append(("현행", cat["lsEfYdInfoGuide"], {"MST": c["MST"], "efYd": c["efYd"]}))
            for p in d["pending"]:
                jobs.append((f"예정_{p['efYd']}", cat["lsEfYdInfoGuide"], {"MST": p["MST"], "efYd": p["efYd"]}))
            if "compare" in d:
                jobs.append(("대조용_묶음버전", cat["lsEfYdInfoGuide"],
                             {"MST": d["compare"]["MST"], "efYd": d["compare"]["efYd"]}))
            jobs.append(("위임", cat["lsDelegated"], {"MST": c["MST"]}))
        else:
            jobs.append(("현행", cat["admrulInfoGuide"], {"ID": d["current"]["ID"]}))
        for name, api, params in jobs:
            out = base / tag / name
            if (out / "response.json").exists():
                continue  # 이미 받은 것은 다시 받지 않는다(원천은 덮어쓰지 않음)
            try:
                get(cl, api, params, out)
                log.append({"doc": tag, "what": name, "status": "ok"})
            except law_api.ApiError as exc:
                log.append({"doc": tag, "what": name, "status": "error", "error": str(exc)})
                print(f"{tag} {name}: 실패 {exc}", file=sys.stderr, flush=True)
        out = base / tag / "별표목록"
        if not out.exists():
            try:
                annex_list(cl, cat["lsBylListGuide" if d["kind"] == "law" else "admrulBylListGuide"], d["title"], out)
                log.append({"doc": tag, "what": "별표목록", "status": "ok"})
            except law_api.ApiError as exc:
                log.append({"doc": tag, "what": "별표목록", "status": "error", "error": str(exc)})
                print(f"{tag} 별표목록: 실패 {exc}", file=sys.stderr, flush=True)
        print(f"{tag} 완료", flush=True)
    law_api.save_json(base / f"받기_기록_{datetime_stamp()}.json", log)


def datetime_stamp():
    from datetime import datetime
    return datetime.now(law_api.KST).strftime("%H%M%S")


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="cmd", required=True)
    s = sub.add_parser("search")
    s.add_argument("--date", required=True)
    b = sub.add_parser("bodies")
    b.add_argument("--date", required=True)
    b.add_argument("--only", type=int, action="append", help="이 doc_id만 (여러 번 지정 가능)")
    args = p.parse_args()
    {"search": cmd_search, "bodies": cmd_bodies}[args.cmd](args)


if __name__ == "__main__":
    main()
