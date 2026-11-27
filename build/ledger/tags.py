"""PDF 태그 구조(Word가 넣은 Table/TR/TD)에서 표 행이 어느 쪽에 걸치는지 읽는다 (표준 라이브러리만).

왜: 처리방침 PDF는 Word로 만든 '태그 PDF'라 문서 안에 표의 행(TR) 구조가 들어 있다. 이 구조는 그림(괘선)에서
행을 복원하는 extract.py와 독립된 근거다. 특히 쪽 경계에서 '잘린 행을 이었나 / 새 행으로 뗐나'는 그림만으로는
추정할 수밖에 없는데, 태그의 TR이 두 쪽에 내용을 가지면 잘린 행이다. 이 모듈은 행마다 내용이 있는 쪽 목록만 뽑는다
(글자는 읽지 않는다: 글꼴 CMap 해독이 필요해서).

구조: Catalog → StructTreeRoot → … → Table → (THead|TBody|TFoot)? → TR → (TD|TH) → … → MCID(정수) 또는 MCR
MCID의 쪽은 그것을 품은 가장 가까운 요소의 /Pg 다.
"""
import re
import zlib
from pathlib import Path

_OBJ = re.compile(rb"(\d+)\s+(\d+)\s+obj\b")
_REF = re.compile(rb"(\d+)\s+0\s+R")


def _stream_after(data, pos):
    s = data.find(b"stream", pos) + 6
    if data[s:s + 2] == b"\r\n":
        s += 2
    elif data[s:s + 1] in (b"\n", b"\r"):
        s += 1
    e = data.find(b"endstream", s)
    return data[s:e]


def load_objects(pdf):
    """{객체 번호: 원문 바이트(사전 부분)}. 압축 객체 묶음(ObjStm)도 푼다."""
    data = Path(pdf).read_bytes()
    objs = {}
    starts = [(int(m.group(1)), m.end()) for m in _OBJ.finditer(data)]
    for i, (num, pos) in enumerate(starts):
        end = data.find(b"endobj", pos)
        objs[num] = data[pos:end]
    for num, body in list(objs.items()):
        if b"/ObjStm" not in body[:400]:
            continue
        head = body[:body.find(b"stream")] if b"stream" in body else body
        n = int(re.search(rb"/N\s+(\d+)", head).group(1))
        first = int(re.search(rb"/First\s+(\d+)", head).group(1))
        raw = zlib.decompress(_stream_after(body, 0))
        nums = list(map(int, raw[:first].split()))
        pairs = [(nums[2 * k], nums[2 * k + 1]) for k in range(n)]
        for k, (onum, off) in enumerate(pairs):
            end = pairs[k + 1][1] if k + 1 < n else len(raw) - first
            objs[onum] = raw[first + off:first + end]
    return objs


def _get(body, key):
    m = re.search(rb"/" + key + rb"\s*(\d+\s+0\s+R|/\w+|\[[^\]]*\]|\d+)", body)
    return m.group(1) if m else None


def page_order(objs):
    cat = next(b for b in objs.values() if re.search(rb"/Type\s*/Catalog", b))
    root = int(_REF.match(_get(cat, b"Pages")).group(1))
    out = []

    def walk(n):
        b = objs[n]
        if re.search(rb"/Type\s*/Pages", b):
            for k in _REF.finditer(_get(b, b"Kids")):
                walk(int(k.group(1)))
        else:
            out.append(n)
    walk(root)
    return {n: i + 1 for i, n in enumerate(out)}


def _kids(body):
    """/K 값: 정수 MCID, 참조, MCR 사전이 섞인 목록 → [('mcid', n, pg|None) | ('ref', n)]"""
    i = body.find(b"/K")
    if i < 0:
        return []
    rest = body[i + 2:].lstrip()
    if rest.startswith(b"["):
        depth, j = 0, 0
        for j, ch in enumerate(rest):
            if ch == 0x5B:
                depth += 1
            elif ch == 0x5D:
                depth -= 1
                if depth == 0:
                    break
        inner = rest[1:j]
    else:
        m = re.match(rb"(\d+\s+0\s+R|\d+|<<.*?>>)", rest, re.S)
        inner = m.group(1) if m else b""
    out = []
    for m in re.finditer(rb"<<(.*?)>>|(\d+)\s+0\s+R|(\d+)", inner, re.S):
        if m.group(1) is not None:
            d = m.group(1)
            mc = re.search(rb"/MCID\s+(\d+)", d)
            pg = re.search(rb"/Pg\s+(\d+)\s+0\s+R", d)
            if mc:
                out.append(("mcid", int(mc.group(1)), int(pg.group(1)) if pg else None))
        elif m.group(2):
            out.append(("ref", int(m.group(2))))
        else:
            out.append(("mcid", int(m.group(3)), None))
    return out


def _stype(body):
    m = re.search(rb"/S\s*/(\w+)", body)
    return m.group(1).decode() if m else ""


def _pg(body):
    m = re.search(rb"/Pg\s+(\d+)\s+0\s+R", body)
    return int(m.group(1)) if m else None


def tables(pdf):
    """[{"rows": [{"pages": [..], "cells": n, "header": bool, "has_content": [칸마다 내용 있음]}]}] 문서 순서."""
    objs = load_objects(pdf)
    pages = page_order(objs)
    elems = {n: b for n, b in objs.items() if b"/StructElem" in b or re.search(rb"/S\s*/\w+", b[:200] or b"")}

    def content_pages(n, inherited=None, seen=None):
        seen = seen if seen is not None else set()
        if n in seen or n not in objs:
            return set()
        seen.add(n)
        b = objs[n]
        pg = _pg(b) or inherited
        out = set()
        for k in _kids(b):
            if k[0] == "mcid":
                p = k[2] or pg
                if p in pages:
                    out.add(pages[p])
            else:
                out |= content_pages(k[1], pg, seen)
        return out

    def children(n):
        return [k[1] for k in _kids(objs[n]) if k[0] == "ref" and k[1] in objs]

    result = []

    def walk(n, seen):
        if n in seen or n not in objs:
            return
        seen.add(n)
        st = _stype(objs[n])
        if st == "Table":
            rows = []

            def rows_of(m, header=False):
                for c in children(m):
                    s = _stype(objs[c])
                    if s in ("THead", "TBody", "TFoot"):
                        rows_of(c, header or s == "THead")
                    elif s == "TR":
                        cells = [x for x in children(c) if _stype(objs[x]) in ("TD", "TH")]
                        hdr = header or all(_stype(objs[x]) == "TH" for x in cells)
                        rows.append({"pages": sorted(content_pages(c)), "cells": len(cells), "header": hdr,
                                     "has_content": [bool(content_pages(x)) for x in cells]})
            rows_of(n)
            result.append({"rows": rows})
            return
        for c in children(n):
            walk(c, seen)

    cat = next(b for b in objs.values() if re.search(rb"/Type\s*/Catalog", b))
    root = int(re.search(rb"/StructTreeRoot\s+(\d+)\s+0\s+R", cat).group(1))
    walk(root, set())
    return result


if __name__ == "__main__":
    import json
    import sys
    t = tables(sys.argv[1])
    print(json.dumps([{"rows": len(x["rows"]), "header_rows": sum(r["header"] for r in x["rows"]),
                       "split_rows": sum(len(r["pages"]) > 1 for r in x["rows"])} for x in t]))
