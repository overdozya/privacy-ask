"""build/emb/ 공통: 경로, 모델 설정, 벡터 바이트 꼴.

A6 보조 검색(서버 pa/tools)이 쓰는 안내서 청크 벡터와 질의용 ONNX 모델을 빌드 때 준비한다.
실행 중(서버)에는 Python이 없다. 여기 스크립트는 빌드 때만 돈다(data/.venv-emb, 레포 밖).

- 문서 벡터: 동료 확정 묶음(ref/.../emb/s1_guide·s2_guide)을 chunk_key로 맞춰 그대로 쓴다.
  안 맞은 청크만 같은 모델·같은 접두(`passage: `)·float32로 새로 만든다.
- 질의 모델: HF 캐시의 nlpai-lab/KURE-v3(rev 73d5e710bc0b)를 ONNX로 내보내고 출력 채널별 int8로 줄인다.
  새로 내려받지 않는다(HF_HUB_OFFLINE=1).
"""
import os
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))  # build/: paths.py
import paths as P  # noqa: E402

os.environ.setdefault("HF_HUB_OFFLINE", "1")
os.environ.setdefault("TRANSFORMERS_OFFLINE", "1")

MODEL = "nlpai-lab/KURE-v3"
REVISION = "73d5e710bc0b"                     # 동료 1단계 문서 벡터와 같은 판(2단계 5b0494e801cd는 모델·토크나이저가 같음)
DIM = 1024                                    # 출력 2048 중 앞 1024, 길이 1로 다시 맞춤
MAX_LEN = 1024
QUERY_PREFIX = "query: "
PASSAGE_PREFIX = "passage: "
VECTOR_BYTES = DIM * 4                        # float32 little-endian (kb_chunks.embedding)

EMB_DIR = P.BUNDLE / "emb"                    # 읽기만
EMB_PARTS = ("s1_guide", "s2_guide")          # 법령 벡터는 쓰지 않는다(A6은 법령을 찾지 않음)
BUNDLE_GUIDE_CHUNKS = (P.BUNDLE_KB / "stage1" / "guide_chunks_current.jsonl",
                       P.BUNDLE_KB / "stage2" / "guide2_chunks_current.jsonl")
BUNDLE_ONNX_MANIFEST = EMB_DIR / "onnx_model_manifest.json"

MODELS_OUT = P.DATA / "models" / "kure-v3-onnx"   # gitignore(data/)
EMB_SQL = "15_kb_embeddings.sql"                  # data/out/mysql/, 10_kb.sql 다음에 넣는다
EMB_REPORT = P.OUT / "emb" / "align_report.json"
QUERY_CHECK = MODELS_OUT / "query_check.json"     # Python 질의 벡터(Java 비교용)

# 질의 비교용 문장: 이 세션이 지은 일반 문장(평가셋 문장 아님).
CHECK_QUERIES = (
    "회원 탈퇴한 사람의 개인정보는 얼마나 보관해도 되나요?",
    "고객 상담 녹취 파일을 외부 업체에 맡겨 분석해도 되나요?",
    "마케팅 문자 발송에 별도 동의가 필요한가요?",
    "CCTV 영상을 경찰이 달라고 하면 줘도 되나요?",
    "가명처리한 데이터를 연구 목적으로 다른 회사에 제공할 수 있나요?",
    "개인정보 유출이 의심될 때 언제까지 신고해야 하나요?",
    "만 14세 미만 아동의 회원가입에 법정대리인 동의를 어떻게 받나요?",
    "해외 클라우드 서버에 고객 정보를 저장하려면 무엇을 알려야 하나요?",
    "직원 출입 기록을 근태 관리에 써도 되나요?",
    "주민등록번호 대신 쓸 수 있는 본인확인 방법은 무엇인가요?",
)


def snapshot_dir(revision: str = REVISION) -> Path:
    """HF 캐시의 KURE-v3 스냅샷 폴더(내려받지 않음). 없으면 멈춘다."""
    hub = Path(os.environ.get("HF_HUB_CACHE", Path.home() / ".cache" / "huggingface" / "hub"))
    base = hub / "models--nlpai-lab--KURE-v3" / "snapshots"
    hits = sorted(p for p in base.glob(revision + "*") if p.is_dir()) if base.is_dir() else []
    if not hits:
        raise SystemExit("HF 캐시에 %s rev %s가 없다(%s). 내려받지 않는다." % (MODEL, revision, base))
    return hits[0]


def to_blob(vec) -> bytes:
    """float32 little-endian 4,096바이트."""
    import numpy as np
    v = np.asarray(vec, dtype="<f4").reshape(-1)
    if v.shape[0] != DIM:
        raise ValueError("차원 %d != %d" % (v.shape[0], DIM))
    return v.tobytes()


def from_blob(b: bytes):
    import numpy as np
    if len(b) != VECTOR_BYTES:
        raise ValueError("바이트 %d != %d" % (len(b), VECTOR_BYTES))
    return np.frombuffer(b, dtype="<f4")
