"""KURE-v3 → 질의용 ONNX(int8, 출력 채널별) 만들기와 확인. 빌드 때 한 번(서버 실행 중에는 Python 없음).

왜 직접 내보내나: KURE-v3(ministral3 구조)는 transformers 5.x가 필요하고 표준 변환 도구가 아직 받지 않는다.
그래서 동료 도구(ref/.../tools/export_kure_onnx.py)와 같은 방법(torch.onnx.export, opset 18, eager attention,
dynamic int8 per-channel)으로 내보낸다. 동료 도구는 평가셋 질문 파일을 읽으므로 쓰지 않고, 확인 문장은
emb_common.CHECK_QUERIES(이 세션이 지은 일반 문장)로 한다.

내보내는 모양: 입력 input_ids·attention_mask(int64 [배치, 길이]) → 출력 last_hidden_state(float32 [배치, 길이, 2048]).
Java(pa/tools)가 할 일: 질의 앞에 `query: `, tokenizer.json으로 토큰화(최대 1024), attention mask 평균 풀링,
앞 1024차원, 길이 1로 정규화.

출력(data/models/kure-v3-onnx/, gitignore):
  model_int8pc.onnx, model_int8pc.onnx.data, tokenizer.json, tokenizer_config.json  (서버가 쓰는 것)
  fp32/model.onnx, fp32/model.onnx_data  (양자화 원본, 서버는 안 씀)
  model_manifest.json (크기·sha256, 동료 manifest와 같은지), query_check.json (Python 질의 벡터: Java 비교용)

실행(build/에서): ../data/.venv-emb/bin/python emb/export_onnx.py [--skip-export] [--skip-quantize]
메모리: 내보내기 중 약 10GB.
"""
import argparse
import hashlib
import json
import random
import shutil
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import emb_common as C  # noqa: E402


def sha256(p: Path) -> str:
    h = hashlib.sha256()
    with open(p, "rb") as f:
        for b in iter(lambda: f.read(1 << 22), b""):
            h.update(b)
    return h.hexdigest()


def load_st(snap: Path):
    import torch
    from sentence_transformers import SentenceTransformer
    m = SentenceTransformer(str(snap), device="cpu", truncate_dim=C.DIM, local_files_only=True,
                            model_kwargs={"dtype": torch.float32})
    m.max_seq_length = C.MAX_LEN
    return m


def st_encode(m, texts, kind):
    import numpy as np
    fn = m.encode_query if kind == "query" else m.encode_document
    E = np.asarray(fn(list(texts), batch_size=4, convert_to_numpy=True), dtype="float32")
    return E / np.linalg.norm(E, axis=1, keepdims=True)


def export_fp32(st, out_fp32: Path, opset: int):
    import onnx
    import torch
    hf = st[0].auto_model
    try:
        hf.config._attn_implementation = "eager"
    except Exception:  # noqa: BLE001
        pass
    hf = hf.float().eval()

    class W(torch.nn.Module):
        def __init__(self, m):
            super().__init__()
            self.m = m

        def forward(self, input_ids, attention_mask):
            return self.m(input_ids=input_ids, attention_mask=attention_mask).last_hidden_state

    tok = st.tokenizer
    ex = tok([C.QUERY_PREFIX + "개인정보 제공에 동의가 필요한가요?", C.PASSAGE_PREFIX + "짧은 예시 문장"],
             padding=True, return_tensors="pt")
    tmp = out_fp32 / "_tmp"
    shutil.rmtree(tmp, ignore_errors=True)
    tmp.mkdir(parents=True)
    t = time.time()
    with torch.no_grad():
        torch.onnx.export(W(hf), (ex["input_ids"], ex["attention_mask"]), str(tmp / "model.onnx"),
                          input_names=["input_ids", "attention_mask"], output_names=["last_hidden_state"],
                          dynamic_axes={"input_ids": {0: "batch", 1: "seq"}, "attention_mask": {0: "batch", 1: "seq"},
                                        "last_hidden_state": {0: "batch", 1: "seq"}},
                          opset_version=opset, dynamo=False)
    print("  내보내기 %.0f초" % (time.time() - t))
    m = onnx.load(str(tmp / "model.onnx"), load_external_data=True)
    onnx.save_model(m, str(out_fp32 / "model.onnx"), save_as_external_data=True, all_tensors_to_one_file=True,
                    location="model.onnx_data", size_threshold=1024)
    del m
    shutil.rmtree(tmp, ignore_errors=True)  # 내보내기 중간 조각(이 스크립트가 방금 만든 것)


def quantize(fp32: Path, q: Path):
    from onnxruntime.quantization import QuantType, quantize_dynamic
    t = time.time()
    quantize_dynamic(str(fp32), str(q), weight_type=QuantType.QInt8, use_external_data_format=True, per_channel=True)
    print("  양자화 %.0f초" % (time.time() - t))


def onnx_embedder(path: Path, tok):
    import numpy as np
    import onnxruntime as ort
    sess = ort.InferenceSession(str(path), providers=["CPUExecutionProvider"])

    def emb(text):
        enc = tok([text], truncation=True, max_length=C.MAX_LEN, return_tensors="np")
        feed = {"input_ids": enc["input_ids"].astype(np.int64), "attention_mask": enc["attention_mask"].astype(np.int64)}
        h = sess.run(["last_hidden_state"], feed)[0].astype(np.float32)
        mk = enc["attention_mask"][..., None].astype(np.float32)
        v = ((h * mk).sum(1) / np.clip(mk.sum(1), 1e-9, None))[:, :C.DIM]
        return (v / np.linalg.norm(v, axis=1, keepdims=True))[0]
    return emb


def sample_docs(n: int, seed: int = 11):
    """동료 문서 벡터와 견줄 안내서 청크 표본(embed_text, 벡터)."""
    import numpy as np
    pos, vecs = {}, []
    for part in C.EMB_PARTS:
        meta = json.loads((C.EMB_DIR / part / "embedding_meta.json").read_text(encoding="utf-8"))
        E = np.load(C.EMB_DIR / part / "embeddings.npy")
        for i, k in enumerate(meta["chunk_keys"]):
            pos[k] = len(vecs)
            vecs.append(E[i])
    chunks = [json.loads(l) for f in C.BUNDLE_GUIDE_CHUNKS for l in open(f, encoding="utf-8") if l.strip()]
    chunks = [c for c in chunks if c["chunk_key"] in pos]
    docs = random.Random(seed).sample(chunks, min(n, len(chunks)))
    return [c["embed_text"] for c in docs], np.stack([vecs[pos[c["chunk_key"]]] for c in docs])


def stats(xs):
    xs = sorted(float(x) for x in xs)
    return {"n": len(xs), "min": round(xs[0], 6), "mean": round(sum(xs) / len(xs), 6), "max": round(xs[-1], 6)}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=str(C.MODELS_OUT))
    ap.add_argument("--opset", type=int, default=18)
    ap.add_argument("--skip-export", action="store_true")
    ap.add_argument("--skip-quantize", action="store_true")
    ap.add_argument("--n-docs", type=int, default=20)
    a = ap.parse_args()
    import numpy as np

    out = Path(a.out)
    fp32_dir = out / "fp32"
    fp32_dir.mkdir(parents=True, exist_ok=True)
    snap = C.snapshot_dir()
    print("[1/5] 원 모델(HF 캐시 %s)" % snap.name)
    st = load_st(snap)
    tok = st.tokenizer
    if not a.skip_export:
        print("[2/5] ONNX 32비트 내보내기")
        export_fp32(st, fp32_dir, a.opset)
    qpath = out / "model_int8pc.onnx"
    if not a.skip_quantize:
        print("[3/5] int8 출력 채널별 양자화")
        quantize(fp32_dir / "model.onnx", qpath)
    for f in ("tokenizer.json", "tokenizer_config.json"):
        shutil.copyfile(snap / f, out / f)

    print("[4/5] 확인: 질의(ST float32 ↔ ONNX int8), 문서(동료 벡터 ↔ ST·ONNX)")
    q_st = st_encode(st, C.CHECK_QUERIES, "query")
    emb8 = onnx_embedder(qpath, tok)
    lat, q8 = [], []
    for q in C.CHECK_QUERIES:
        t = time.perf_counter()
        q8.append(emb8(C.QUERY_PREFIX + q))
        lat.append((time.perf_counter() - t) * 1000)
    q8 = np.stack(q8)
    cos_q = (q_st * q8).sum(1)
    texts, ref = sample_docs(a.n_docs)
    d_st = st_encode(st, texts, "document")
    d8 = np.stack([emb8(C.PASSAGE_PREFIX + t) for t in texts])
    cos_d_st = (d_st * ref).sum(1)
    cos_d8 = (d8 * ref).sum(1)
    same_top = float(np.mean(np.argmax(q8 @ ref.T, 1) == np.argmax(q_st @ ref.T, 1)))
    check = {
        "model": C.MODEL, "revision": C.REVISION, "dim": C.DIM, "query_prefix": C.QUERY_PREFIX,
        "note": "st = sentence-transformers float32(질의), onnx_py = 같은 int8 ONNX를 Python onnxruntime으로. Java 벡터는 서버 테스트가 이 파일과 견준다",
        "cos_query_st_vs_onnx_int8": stats(cos_q),
        "cos_doc_bundle_vs_st_float32": stats(cos_d_st),
        "cos_doc_bundle_vs_onnx_int8": stats(cos_d8),
        "same_top1_in_doc_sample": same_top,
        "onnx_py_query_ms": stats(lat),
        "queries": [{"text": q, "st": [round(float(x), 7) for x in s], "onnx_py": [round(float(x), 7) for x in o]}
                    for q, s, o in zip(C.CHECK_QUERIES, q_st, q8)],
    }
    (out / "query_check.json").write_text(json.dumps(check, ensure_ascii=False) + "\n", encoding="utf-8")
    for k in ("cos_query_st_vs_onnx_int8", "cos_doc_bundle_vs_st_float32", "cos_doc_bundle_vs_onnx_int8", "onnx_py_query_ms"):
        print("  %s %s" % (k, check[k]))
    print("  same_top1_in_doc_sample %.2f" % same_top)

    print("[5/5] 파일 manifest")
    peer = {f["name"]: f for f in json.loads(C.BUNDLE_ONNX_MANIFEST.read_text(encoding="utf-8"))["files"]}
    files = []
    for name in ("model_int8pc.onnx", "model_int8pc.onnx.data", "tokenizer.json", "tokenizer_config.json"):
        p = out / name
        e = {"name": name, "bytes": p.stat().st_size, "sha256": sha256(p)}
        if name in peer:
            e["same_as_bundle_manifest"] = e["sha256"] == peer[name]["sha256"]
        files.append(e)
        print("  %s %d %s" % (name, e["bytes"], e.get("same_as_bundle_manifest")))
    man = {"model": C.MODEL, "revision": C.REVISION, "snapshot": snap.name,
           "format": "onnx int8 (per-channel, dynamic quantization)", "opset": a.opset,
           "inputs": ["input_ids", "attention_mask"], "output": "last_hidden_state",
           "query_prefix": C.QUERY_PREFIX, "pooling": "mean (attention_mask)", "dim": C.DIM, "max_length": C.MAX_LEN,
           "files": files}
    (out / "model_manifest.json").write_text(json.dumps(man, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
