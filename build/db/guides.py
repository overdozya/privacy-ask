"""안내서 25종(확정 묶음 kb/stage1·stage2)을 DB 행으로 옮기고, section_path로 절 목록(guide_sections)을 만든다.

- 글자, chunk_key, text_hash, article은 묶음 그대로 둔다(다시 자르거나 고치지 않는다).
- 절 ID는 (문서, 목차 경로)의 sha256 앞 10자리라서 같은 묶음이면 다시 빌드해도 같다.
  묶음에 절이 더해져도 다른 절의 ID는 바뀌지 않는다(번호 매기기 방식과 다른 점).
- 청크는 자기 section_path에 해당하는 잎 노드 하나에 속한다. 경로 노드가 자식 절도 갖고 자기 청크도 가지면
  (장 제목 바로 아래 본문 등) 그 청크는 '(본문)' 잎 노드에 넣는다. 그래야 모든 청크가 잎 하나에 속한다.
"""
from __future__ import annotations

import hashlib
import json
from pathlib import Path

GUIDE_FILES = [("stage1/guide_documents.jsonl", "stage1/guide_chunks_current.jsonl"),
               ("stage2/guide2_documents.jsonl", "stage2/guide2_chunks_current.jsonl")]
OWN_TEXT = "(본문)"  # 자식 절이 있는 노드 자신의 청크를 담는 잎


def sha(s: bytes | str) -> str:
    return hashlib.sha256(s.encode("utf-8") if isinstance(s, str) else s).hexdigest()


def section_id(doc_id: int, path: tuple) -> str:
    return f"G{doc_id:02d}-" + sha("\x1f".join(path))[:10]


def load_guides(bundle: Path, created_at: str):
    """묶음 파일 → (문서 dict 목록, 청크 dict 목록, 입력 파일 해시). 청크는 doc_id, 파일 줄 순서."""
    docs, chunks, files = {}, [], {}
    for doc_f, chunk_f in GUIDE_FILES:
        for f in (doc_f, chunk_f):
            files[f] = sha((bundle / f).read_bytes())
        for line in (bundle / doc_f).read_text(encoding="utf-8").splitlines():
            d = json.loads(line)
            docs[d["doc_id"]] = d
        for no, line in enumerate((bundle / chunk_f).read_text(encoding="utf-8").splitlines(), 1):
            r = json.loads(line)
            assert sha(r["text"]) == r["text_hash"], r["chunk_key"]
            assert r["status"] == "CURRENT"
            r["_src"] = f"묶음:kb/{chunk_f}#L{no}"
            r["_line"] = line
            chunks.append(r)
    chunks.sort(key=lambda r: r["doc_id"])  # 안정 정렬: 같은 문서 안에서는 파일 줄 순서 그대로

    doc_rows, chunk_rows = [], []
    seq = {}
    for r in chunks:
        d = docs[r["doc_id"]]
        assert r["guide"] == d["title"] and tuple(r["section_path"])[0] == d["title"]
        seq[r["doc_id"]] = seq.get(r["doc_id"], 0) + 1
        chunk_rows.append(dict(
            doc_id=r["doc_id"], article=r["article"], text=r["text"], status="CURRENT", effective_from=None,
            effective_to=None, text_hash=r["text_hash"], unit_key=f"G{r['doc_id']:02d}|{seq[r['doc_id']]:04d}",
            jo=None, jo_sub=None, hang=None, ho=None, ho_from=None, ho_to=None, title=None, source_file=r["_src"],
            chunk_key=r["chunk_key"], page_from=r["pages"][0], page_to=r["pages"][1],
            _path=tuple(r["section_path"])))
    for doc_id in sorted(docs):
        d = docs[doc_id]
        lines = "\n".join(r["_line"] for r in chunks if r["doc_id"] == doc_id)
        doc_rows.append(dict(
            doc_id=doc_id, source_type="GUIDE", title=d["title"], law_name=d["title"], law_id=None,
            law_serial_no=None, case_no=None, promulgation_date=None, effective_date=d.get("effective_date"),
            stage=d["stage"], url=None, content_hash=sha(lines), sync_source=d["sync_source"], verified=0,
            last_checked_at=None, created_at=created_at, law_kind="안내서", api_target=None, promulgation_no=None,
            parent_doc_id=None, use_policy=None, use_policy_by=None, use_policy_at=None,
            use_policy_note=None, guide_version=d.get("version"), page_count=d.get("pages"),
            excluded_pages=json.dumps(d["excluded"], ensure_ascii=False) if d.get("excluded") else None))
    return doc_rows, chunk_rows, files


def build_sections(chunk_rows):
    """chunk_id가 붙은 안내서 청크 → guide_sections 행 목록. 청크에 section_id를 적는다."""
    nodes = {}   # (doc, path) → node
    order = []

    def node(doc, path, synthetic=False):
        k = (doc, path)
        if k not in nodes:
            parent = None
            if len(path) > 1:
                parent = node(doc, path[:-1])["section_id"]
            n = dict(section_id=section_id(doc, path), doc_id=doc, parent_id=parent, depth=len(path),
                     title=path[-1], path=json.dumps(list(path), ensure_ascii=False),
                     path_text=" > ".join(path), synthetic=1 if synthetic else 0, _path=path, _children=[],
                     _own=[])
            if parent:
                nodes[(doc, path[:-1])]["_children"].append(n)
            nodes[k] = n
            order.append(n)
        return nodes[k]

    # 1) 경로 노드 만들기
    for c in chunk_rows:
        node(c["doc_id"], c["_path"])
    # 2) 청크를 잎에 넣기. 자식이 있는 노드의 자기 청크는 '(본문)' 잎으로
    for c in chunk_rows:
        n = nodes[(c["doc_id"], c["_path"])]
        if n["_children"] and not n["synthetic"]:
            n = node(c["doc_id"], c["_path"] + (OWN_TEXT,), synthetic=True)
        n["_own"].append(c)
        c["section_id"] = n["section_id"]
    # 3) '(본문)' 잎은 형제 중 맨 앞에 둔다(본문이 하위 절보다 먼저 나오므로)
    for n in order:
        n["_children"].sort(key=lambda x: (0 if x["synthetic"] else 1))
    # 4) 전위 순회로 순서·번호·크기
    rows = []

    def walk(n, num):
        n["ord"] = len(rows) + 1
        n["num"] = num
        rows.append(n)
        chunks = list(n["_own"])
        i = 0
        for ch in n["_children"]:
            if ch["synthetic"]:
                sub = walk(ch, (num + "." if num else "") + "0")
            else:
                i += 1
                sub = walk(ch, (num + "." if num else "") + str(i))
            chunks.extend(sub)
        ids = sorted(c["chunk_id"] for c in chunks)
        runs = sum(1 for j, x in enumerate(ids) if j == 0 or ids[j - 1] != x - 1)
        n.update(is_leaf=0 if n["_children"] else 1,
                 page_from=min(c["page_from"] for c in chunks), page_to=max(c["page_to"] for c in chunks),
                 chunk_from=ids[0], chunk_to=ids[-1], chunk_count=len(ids), own_chunk_count=len(n["_own"]),
                 char_count=sum(len(c["text"]) for c in chunks), chunk_runs=runs)
        return chunks

    for n in order:
        if n["parent_id"] is None:
            walk(n, "")
    return rows
