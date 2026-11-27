"""빌드 스크립트·테스트가 쓰는 경로를 한 곳에 모은다.

레포 구조(README.md): build/(이 폴더, 추적), data/·ref/·docs/(git 밖, 로컬에만).
- data/원천/   법제처 API 응답(받은 그대로)
- data/수집/   사람이 정한 빌드 입력(대상·대조표·사용 정책·꾸러미·04 대응표·쪽 경계 수정·예시 사안)
- data/out/    빌드 산출(SQLite DB, 검토표, MySQL 적재 파일). 입력이 같으면 같은 파일이 다시 나온다
- ref/         읽기 전용 참고 자료(확정 묶음, 산출물, 처리방침 PDF, 지난 실험)

DB 안에 남기는 출처 글(`원천/20261125`, `수집/꾸러미_v2.json`)은 DATA 기준 상대 경로다.
"""
from pathlib import Path

BUILD = Path(__file__).resolve().parent
REPO = BUILD.parent
DATA = REPO / "data"
REF = REPO / "ref"
TOOLS = REPO / "tools"

# ---- 입력
SOURCE = DATA / "원천"                                   # 날짜 폴더마다 한 번의 수집
SOURCE_DEFAULT = SOURCE / "20261125"
INPUT = DATA / "수집"
TARGETS = INPUT / "대상_25종.json"
SELECTION = INPUT / "선정_대조표.json"
POLICY_FILE = INPUT / "사용정책_결정.json"
PACKAGE_FILES = (INPUT / "꾸러미_v1.json", INPUT / "꾸러미_v2.json")
FACT_MAP = INPUT / "04_쟁점사실_대응표_20261126.tsv"
LEDGER_OVERRIDES = INPUT / "쪽경계_수정.json"
EXAMPLE_CASE = INPUT / "예시사안.json"

# ---- 참고(읽기만)
BUNDLE = REF / "PrivacyAsk_RAG실험묶음_20261125"
BUNDLE_KB = BUNDLE / "kb"
SEED_1116 = REF / "PrivacyAsk_산출물_20261116" / "04_기준데이터" / "privacy-ask-seed_20261116" / "seed"
RULE_SET = SEED_1116 / "rule_set_v1.0.json"
REGISTRY_1116 = REF / "PrivacyAsk_산출물_20261116" / "04_기준데이터" / "PrivacyAsk_RAG_등록목록_20261116.xlsx"
KB_SOURCES_CURRENT = REF / "PrivacyAsk_산출물_현행" / "04_기준데이터" / "privacy-ask-seed" / "seed" / "kb_sources.csv"
POLICY_PDFS = REF / "개인정보처리방침_20261028"
EXP03_LEDGER = REF / "실험" / "03_실제원장_대조" / "data" / "ledger"
LAW_API = TOOLS / "law-api"

# ---- 산출
OUT = DATA / "out"
DB_OUT = OUT / "db"
KB_DB = DB_OUT / "privacyask.db"
LEDGER_OUT = OUT / "ledger"
LEDGER_DB = LEDGER_OUT / "ledger.db"
LEDGER_CACHE = LEDGER_OUT / "cache"
LEDGER_CHECK = LEDGER_OUT / "점검"
CASE_OUT = OUT / "case"
CASE_DB = CASE_OUT / "case.db"
CASE_REPLAY = CASE_OUT / "예시사안_재현표.md"
MYSQL_OUT = OUT / "mysql"

# ---- 레포 밖(있을 때만 쓰는 대조용). 없으면 그 테스트는 건너뛴다
TEAM_V1 = REPO.parent / "backend" / "data" / "local-init" / "V1__create_schema.sql"
