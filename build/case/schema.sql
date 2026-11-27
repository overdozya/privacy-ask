-- 사안 기록(D9) SQLite 판. 테스트·예시용. 운영(MySQL 8)은 schema_mysql.sql(같은 테이블·같은 칸).
-- 계획: 03_DB_에이전트_설계계획 3.2 D9, 4절 A0~A8. 사실 ID·허용 값: 04_쟁점사실_대응표_20261126.tsv.
-- 이식성: SQLite 전용 기능(동적 타입에 기대기, WITHOUT ROWID, 부분 인덱스, STRICT)을 쓰지 않는다.
--   날짜는 'YYYY-MM-DD'(MySQL DATE), 시각은 'YYYY-MM-DD HH:MM:SS.fff' Asia/Seoul(MySQL DATETIME(3)) 글자로 넣는다.
--   JSON 칸은 json_valid 검사(MySQL은 JSON 형), 참·거짓은 0/1(MySQL TINYINT(1)).
-- 거부 규칙: 칸 제약(NOT NULL·CHECK·FK)으로 되는 것은 여기서, 다른 DB(privacyask.db·ledger.db)를 봐야 하는 것은
--   case/store.py가 막는다. 규칙 목록과 번호(C01~C17)는 case/4b단계_사안기록_결과.md 3절.

PRAGMA foreign_keys = ON;

-- ───────── 기준표: 입력 파일에서 그대로 읽어 넣는다(손으로 옮겨 적지 않음). 값 검사를 FK로 하려고 DB에 둔다 ─────────

-- 기준 파일과 해시. 판정이 어느 대응표·규칙 세트로 됐는지 남기는 기준
CREATE TABLE catalog_sources (
  name        VARCHAR(40)  NOT NULL PRIMARY KEY,  -- fact_map(04 대응표 tsv) / rule_set(현업 rule_set_v1.0.json)
  path        VARCHAR(300) NOT NULL,              -- mainproject/ 기준 상대 경로
  version     VARCHAR(40)  NOT NULL,              -- 파일 이름의 날짜 / rule_set version
  sha256      CHAR(64)     NOT NULL
);

-- 04 대응표의 사실 46개(tsv 한 줄 = 한 행)
CREATE TABLE fact_catalog (
  fact_id       VARCHAR(10)  NOT NULL PRIMARY KEY, -- F-S1 / F-D1 … / L1~L4
  ord           INTEGER      NOT NULL,             -- tsv 줄 순서
  slot          VARCHAR(60)  NOT NULL,             -- 공통 / data_categories / legal_basis·purpose_scope …(tsv 그대로)
  is_common     INTEGER      NOT NULL CHECK (is_common IN (0,1)),  -- 1 = 슬롯 '공통': 사안 단위(흐름 없음)
  fact_usage    VARCHAR(40)  NOT NULL,             -- 쓰임(슬롯 값 / 꾸러미 조건 / 출구(해석 표시) / 검토 묶음 …)
  question      TEXT         NOT NULL,
  allowed_raw   TEXT         NOT NULL,             -- 허용 값 칸 원문
  is_multi      INTEGER      NOT NULL CHECK (is_multi IN (0,1)),   -- 허용 값에 '(복수)'가 있으면 1
  ledger_lookup TEXT         NOT NULL,             -- 원장 조회 칸 원문('—' = 원장 출처 불가)
  ledger_attachments TEXT    NOT NULL CHECK (json_valid(ledger_attachments)), -- 원장 출처로 받을 수 있는 첨부 ["첨부1","첨부2"]
  on_unknown    TEXT         NOT NULL,
  basis_keys    TEXT         NOT NULL,
  interp        VARCHAR(40)  NOT NULL
);

-- 사실마다 고를 수 있는 값. facts 값은 이 표에 FK로 묶인다(허용 값 밖이면 저장 안 됨)
CREATE TABLE fact_options (
  fact_id     VARCHAR(10)  NOT NULL REFERENCES fact_catalog(fact_id),
  ord         INTEGER      NOT NULL,
  label       VARCHAR(120) NOT NULL,              -- tsv 값 글자 그대로('(복수)' 표시만 뗌). '해당 항목'은 질문 문장의 보기로 펼침
  kind        VARCHAR(10)  NOT NULL CHECK (kind IN ('CHOICE','DETAIL','UNKNOWN','MATCH','NO_MATCH')),
              -- CHOICE 보기 / DETAIL 자유 입력이 따라옴(detail 칸 필수) / UNKNOWN '잘 모르겠음'·'모름'(현업만 고름)
              -- MATCH 원장 일치(L 사실, 원장 행 필수) / NO_MATCH 원장 일치 없음(L 사실)
  PRIMARY KEY (fact_id, label)
);

-- 현업 슬롯 8개(rule_set_v1.0 slot_dictionary의 purpose=JUDGE)
CREATE TABLE slot_catalog (
  slot_key         VARCHAR(40) NOT NULL PRIMARY KEY,
  label            VARCHAR(40) NOT NULL,
  value_type       VARCHAR(10) NOT NULL CHECK (value_type IN ('SINGLE','MULTI')),
  ask_condition    TEXT        CHECK (ask_condition IS NULL OR json_valid(ask_condition)),
  rule_set_version VARCHAR(10) NOT NULL,
  sort_order       INTEGER     NOT NULL
);

CREATE TABLE slot_options (
  slot_key  VARCHAR(40) NOT NULL REFERENCES slot_catalog(slot_key),
  ord       INTEGER     NOT NULL,
  value     VARCHAR(60) NOT NULL,                  -- allowed_values 글자 그대로
  PRIMARY KEY (slot_key, value)
);

-- ───────── 사안 기록 ─────────

-- 사안 = 문의 한 건(팀 inquiries와 칸 이름을 맞춤)
CREATE TABLE cases (
  case_id        CHAR(36)     NOT NULL PRIMARY KEY, -- UUID 모양. 법무검토 접수 멱등 키
  requester_id   VARCHAR(20)  NOT NULL,             -- 문의자 사번(팀 users.user_id와 같은 형). 본인 사안만 보이게 하는 기준. users FK는 두지 않음(팀 테이블, SQLite에 없음)
  content        TEXT         NOT NULL,             -- 문의 원문. 출처 INQUIRY 사실의 근거 문구는 이 글 안에 있어야 함
  service_name   VARCHAR(200),                      -- 현업이 적은 서비스명(자유 입력, 없으면 NULL)
  intake_type    VARCHAR(12)  CHECK (intake_type IS NULL OR intake_type IN ('GENERAL','SPECIFIC','OUT_OF_SCOPE')),  -- A0 코드 판정
  out_of_scope_kind VARCHAR(20) CHECK (out_of_scope_kind IS NULL OR out_of_scope_kind IN ('BREACH','DATA_SUBJECT_REQUEST','OUTSIDE_KB_LAW')),
                                                    -- 범위 밖 종류: 유출 사고 / 정보주체 권리 요청 / KB 밖 법령
  state          VARCHAR(12)  NOT NULL CHECK (state IN ('ASKING','JUDGED','TRANSFERRED')), -- 질문 중 / 판정됨 / 이관됨
  rule_set_version VARCHAR(10) NOT NULL,            -- 사안 시작 때 고정한 판(06 2.4-3). 판정 행의 같은 칸이 이 값과 같아야 함(C12)
  package_version  VARCHAR(10) NOT NULL,            -- 쟁점 꾸러미 판. 없는 판이면 저장 안 됨(C11)
  ledger_version   VARCHAR(20),                     -- 처리방침 판(없으면 NULL: 원장 없이 시작)
  reference_date VARCHAR(10),                       -- 사안 기준일(A4). MySQL DATE
  reference_date_basis VARCHAR(10) CHECK (reference_date_basis IS NULL OR reference_date_basis IN ('INQUIRY','TODAY')), -- 문의(F-S3) / 오늘
  created_at     VARCHAR(23)  NOT NULL,
  updated_at     VARCHAR(23)  NOT NULL,
  closed_at      VARCHAR(23),
  CHECK (intake_type = 'OUT_OF_SCOPE' OR out_of_scope_kind IS NULL)
);

-- 단계 기록: A0~A8의 입력·출력. LLM 호출은 프롬프트 ID·버전·모델·온도가 없으면 저장 안 됨(C17)
-- [하위 테이블 추가 이유] 계획의 turns는 질문·답만 담는다. 완료 기준("모든 단계의 입력·출력이 이 테이블만으로 다시 보임")과
--   AGENTS.md("호출 기록에 프롬프트 ID와 버전")를 채우려면 코드 단계·LLM 호출·도구 호출마다 한 행이 필요하다(팀 llm_call_logs 자리)
CREATE TABLE steps (
  case_id        CHAR(36)     NOT NULL REFERENCES cases(case_id),
  seq            INTEGER      NOT NULL,             -- 사안 안 순서(1부터)
  step           VARCHAR(2)   NOT NULL CHECK (step IN ('A0','A1','A2','A3','A4','A5','A6','A7','A8')),
  flow_no        INTEGER,                           -- 흐름 하나에 대한 단계면 그 흐름
  actor          VARCHAR(5)   NOT NULL CHECK (actor IN ('LLM','CODE','TOOL')),
  name           VARCHAR(60)  NOT NULL,             -- 하는 일 한 줄(예: 원장 후보 행 열거)
  prompt_id      VARCHAR(40),
  prompt_version VARCHAR(20),                       -- '<버전>@<보낸 글 sha256 앞 8자>'(예 1@3f2a9c1d). 형식은 저장 함수가 검사(06 2.4-5)
  model_name     VARCHAR(60),
  temperature    REAL,                              -- MySQL DECIMAL(3,2)
  code_version   VARCHAR(40),                       -- CODE 단계: 규칙 세트·꾸러미 등 그 단계가 쓴 판
  input_json     TEXT         NOT NULL CHECK (json_valid(input_json)),
  output_json    TEXT         NOT NULL CHECK (json_valid(output_json)),
  status         VARCHAR(12)  NOT NULL CHECK (status IN ('OK','RETRY_OK','LLM_FAILED')),
  attempts       INTEGER      NOT NULL DEFAULT 1 CHECK (attempts IN (1,2,3)),  -- 검증 실패·일시 오류를 각각 1회 재시도하면 한 호출이 최대 3번(06 F3)
  at             VARCHAR(23)  NOT NULL,
  PRIMARY KEY (case_id, seq),
  CHECK (actor <> 'LLM' OR (prompt_id IS NOT NULL AND prompt_version IS NOT NULL AND model_name IS NOT NULL AND temperature IS NOT NULL)),
  CHECK (status = 'OK' OR actor = 'LLM')
);

-- 처리 흐름(04 결정 4: 흐름마다 슬롯 한 벌)
CREATE TABLE flows (
  case_id      CHAR(36)     NOT NULL REFERENCES cases(case_id),
  flow_no      INTEGER      NOT NULL CHECK (flow_no >= 1),
  label        VARCHAR(200) NOT NULL,               -- A1이 흐름 목록(F-S2)에서 나눈 이름
  origin       VARCHAR(200) NOT NULL,               -- 출발(누가 가진 정보인가)
  destination  VARCHAR(200) NOT NULL,               -- 도착(어디로 가나. 회사 안이면 그 쓰임)
  service_name VARCHAR(200),
  created_step INTEGER      NOT NULL,
  PRIMARY KEY (case_id, flow_no),
  FOREIGN KEY (case_id, created_step) REFERENCES steps(case_id, seq)
);

-- 질문과 답. 질문은 어떤 사실을 묻는지(fact_id) 함께 저장
CREATE TABLE turns (
  case_id      CHAR(36)     NOT NULL REFERENCES cases(case_id),
  turn_no      INTEGER      NOT NULL CHECK (turn_no >= 1),
  role         VARCHAR(5)   NOT NULL CHECK (role IN ('AGENT','USER')),
  kind         VARCHAR(10)  NOT NULL CHECK (kind IN ('QUESTION','ANSWER')),
  flow_no      INTEGER,                             -- 질문이 걸린 흐름(공통 사실이면 NULL)
  fact_id      VARCHAR(10)  REFERENCES fact_catalog(fact_id),
  ask_no       INTEGER      CHECK (ask_no IS NULL OR ask_no IN (1,2)),  -- 1 = 처음, 2 = 다른 표현으로 다시(04 1.9)
  reply_to     INTEGER,                             -- 답이 받는 질문 turn_no
  text         TEXT         NOT NULL,               -- 화면 문장(질문) / 현업이 고르거나 적은 글(답)
  choices_json TEXT         NOT NULL CHECK (json_valid(choices_json)), -- 질문: 보여 준 보기 / 답: 고른 보기
  step_seq     INTEGER      NOT NULL,               -- 질문을 고른 A2 단계 / 답을 받은 단계
  at           VARCHAR(23)  NOT NULL,
  PRIMARY KEY (case_id, turn_no),
  FOREIGN KEY (case_id, step_seq) REFERENCES steps(case_id, seq),
  FOREIGN KEY (case_id, flow_no) REFERENCES flows(case_id, flow_no),
  FOREIGN KEY (case_id, reply_to) REFERENCES turns(case_id, turn_no),
  CHECK (kind <> 'QUESTION' OR (role = 'AGENT' AND fact_id IS NOT NULL AND ask_no IS NOT NULL AND reply_to IS NULL)),
  CHECK (kind <> 'ANSWER' OR (role = 'USER' AND reply_to IS NOT NULL))
);

-- 흐름별 사실. 덮어쓰지 않고 판(rev)을 쌓는다: 모름 → 질문 → 답의 경과가 남아야 이어하기·재질문·재현이 된다
CREATE TABLE facts (
  fact_row_id   INTEGER      NOT NULL PRIMARY KEY,  -- MySQL BIGINT AUTO_INCREMENT. 같은 입력이면 같은 번호
  case_id       CHAR(36)     NOT NULL REFERENCES cases(case_id),
  flow_no       INTEGER,                            -- NULL = 사안 공통 사실(F-S1~F-S3)
  fact_id       VARCHAR(10)  NOT NULL REFERENCES fact_catalog(fact_id),
  rev           INTEGER      NOT NULL CHECK (rev >= 1),
  status        VARCHAR(12)  NOT NULL CHECK (status IN ('CONFIRMED','MATCHED','INFERRED','NOT_FOUND','UNKNOWN')),
                -- 현업 확인 / 원장 명시 일치로 정함 / 추정(포괄 일치·일치 없음·문의 추정) / 모름(아직 값 없음) / 현업 미확인(현업이 '잘 모르겠음'을 고름. 팀 UNKNOWN과 같은 뜻)
  source_kind   VARCHAR(8)   NOT NULL CHECK (source_kind IN ('INQUIRY','ANSWER','LEDGER')),  -- 문의 원문 / 답변 턴 / 원장 조회
  source_turn   INTEGER,                            -- ANSWER: 그 답 turn_no
  source_step   INTEGER      NOT NULL,              -- 이 사실을 적은 단계(A1 추출, A3 원장 판정 등)
  basis_quote   TEXT,                               -- 근거 문구. 여러 군데면 ' … '로 잇고, 조각마다 출처 글 안에 있어야 함
  ledger_version VARCHAR(20),                       -- LEDGER: 처리방침 판(ledger_versions.version_id)
  note          TEXT,
  recorded_at   VARCHAR(23)  NOT NULL,
  UNIQUE (case_id, flow_no, fact_id, rev),
  UNIQUE (fact_row_id, fact_id),
  FOREIGN KEY (case_id, flow_no) REFERENCES flows(case_id, flow_no),
  FOREIGN KEY (case_id, source_turn) REFERENCES turns(case_id, turn_no),
  FOREIGN KEY (case_id, source_step) REFERENCES steps(case_id, seq),
  CHECK (source_kind <> 'ANSWER' OR source_turn IS NOT NULL),
  CHECK (source_kind <> 'LEDGER' OR ledger_version IS NOT NULL),
  CHECK (source_kind = 'LEDGER' OR ledger_version IS NULL)
);
CREATE INDEX idx_facts_case ON facts (case_id, flow_no, fact_id);

-- [하위 테이블] 사실 값. 고른 보기 하나 = 한 행. (fact_id, label) FK로 허용 값 밖 값은 DB가 거부한다(C08)
CREATE TABLE fact_values (
  fact_row_id INTEGER      NOT NULL,
  fact_id     VARCHAR(10)  NOT NULL,
  ord         INTEGER      NOT NULL,
  label       VARCHAR(120) NOT NULL,
  detail      TEXT,                                 -- DETAIL 보기의 자유 입력(서비스명·날짜·나라·법령 조문·이익)
  PRIMARY KEY (fact_row_id, ord),
  FOREIGN KEY (fact_row_id, fact_id) REFERENCES facts(fact_row_id, fact_id),
  FOREIGN KEY (fact_id, label) REFERENCES fact_options(fact_id, label)
);

-- [하위 테이블] 원장 출처 사실이 기댄 처리 원장 행. 처리방침이 개정되면 이 행으로 영향받은 사안을 찾는다(A8)
CREATE TABLE fact_ledger_rows (
  fact_row_id INTEGER     NOT NULL REFERENCES facts(fact_row_id),
  ord         INTEGER     NOT NULL,
  row_id      VARCHAR(20) NOT NULL,                 -- ledger_rows.row_id(예 첨부1-0040). 있는지는 store.py가 ledger.db에서 확인(C06)
  PRIMARY KEY (fact_row_id, ord)
);
CREATE INDEX idx_fact_ledger_row ON fact_ledger_rows (row_id);

-- 판(rev)이 가장 큰 사실 = 지금 상태
CREATE VIEW facts_current AS
SELECT f.* FROM facts f
WHERE NOT EXISTS (
  SELECT 1 FROM facts g
  WHERE g.case_id = f.case_id AND g.fact_id = f.fact_id AND g.rev > f.rev
    AND (g.flow_no = f.flow_no OR (g.flow_no IS NULL AND f.flow_no IS NULL)));

-- 판정(A8 기록 항목 전부). 팀 judgments에 버전·기준일·인용 해시를 더한 것. 팀 judgments·judgment_issues와 이름이 겹쳐 case_ 접두(06 2.4-1)
CREATE TABLE case_judgments (
  judgment_id      INTEGER      NOT NULL PRIMARY KEY,
  case_id          CHAR(36)     NOT NULL REFERENCES cases(case_id),
  step_seq         INTEGER      NOT NULL,           -- 이 판정을 적은 A8 단계
  judged_turn      INTEGER,                         -- 판정에 들어간 마지막 턴(질문이 없었으면 NULL)
  reference_date   VARCHAR(10)  NOT NULL,           -- 사안 기준일. MySQL DATE
  rule_set_version VARCHAR(10)  NOT NULL,           -- 현업 규칙 세트(v1.0)
  package_version  VARCHAR(10)  NOT NULL,           -- 쟁점 꾸러미 판(v2). 없으면 저장 안 됨(C11)
  ledger_version   VARCHAR(20),                     -- 처리방침 판(20261028). 원장 사실·인용을 썼으면 필수(C12)
  fact_map_sha256  CHAR(64)     NOT NULL,           -- 04 대응표 tsv 해시(catalog_sources와 같아야 함)
  model_name       VARCHAR(60)  NOT NULL,           -- 이 사안에서 부른 LLM
  temperature      REAL         NOT NULL,
  prompt_versions  TEXT         NOT NULL CHECK (json_valid(prompt_versions)), -- [{"prompt_id","version"}] = 이 사안 LLM 단계의 모음
  case_grade       CHAR(1)      CHECK (case_grade IS NULL OR case_grade IN ('H','M','L')), -- 쟁점 최고 등급(일반 질의는 NULL)
  exit_type        VARCHAR(10)  NOT NULL CHECK (exit_type IN ('ANSWER','GUIDE','TRANSFER')),
  exit_reasons     TEXT         NOT NULL CHECK (json_valid(exit_reasons)), -- 출구 사유 목록(고 쟁점, 현업 미확인, 결론 미확정 …)
  conclusive       INTEGER      NOT NULL CHECK (conclusive IN (0,1)),       -- 코드 판단(해석 쟁점·사실 충돌이면 0)
  flags            TEXT         NOT NULL CHECK (json_valid(flags)),        -- 사실 충돌·확인 사항·누락 감시 표시
  review_bundle    TEXT         CHECK (review_bundle IS NULL OR json_valid(review_bundle)), -- 검토 요청 묶음(이관이면 필수)
  premise_note     VARCHAR(200) NOT NULL,           -- '제시된 사실관계를 전제로 한 의견'(팀 칸)
  created_at       VARCHAR(23)  NOT NULL,
  UNIQUE (judgment_id, case_id),
  FOREIGN KEY (case_id, step_seq) REFERENCES steps(case_id, seq),
  FOREIGN KEY (case_id, judged_turn) REFERENCES turns(case_id, turn_no),
  CHECK (exit_type <> 'TRANSFER' OR review_bundle IS NOT NULL)
);

-- [하위 테이블] 흐름별 슬롯 상태. 흐름이 둘이면 슬롯 한 벌씩(04 결정 4), 복수 슬롯은 '값 + 일부 미확인'(결정 2)
CREATE TABLE case_judgment_slots (
  judgment_id  INTEGER     NOT NULL,
  case_id      CHAR(36)    NOT NULL,
  flow_no      INTEGER     NOT NULL,
  slot_key     VARCHAR(40) NOT NULL REFERENCES slot_catalog(slot_key),
  status       VARCHAR(10) NOT NULL CHECK (status IN ('CONFIRMED','PARTIAL','UNKNOWN','SKIPPED')),
               -- 확정 / 값 + 일부 미확인(복수 슬롯만) / 현업 미확인 / 질문 조건으로 묻지 않음
  basis_facts  TEXT        NOT NULL CHECK (json_valid(basis_facts)),  -- 이 값을 만든 facts.fact_row_id 목록(그 판 그대로)
  unknown_facts TEXT       NOT NULL CHECK (json_valid(unknown_facts)), -- 현업 미확인으로 남은 사실 ID
  PRIMARY KEY (judgment_id, flow_no, slot_key),
  FOREIGN KEY (judgment_id, case_id) REFERENCES case_judgments(judgment_id, case_id),
  FOREIGN KEY (case_id, flow_no) REFERENCES flows(case_id, flow_no)
);

-- [하위 테이블] 슬롯 값. (slot_key, value) FK로 허용 값 밖 값은 DB가 거부한다(C14)
CREATE TABLE case_judgment_slot_values (
  judgment_id INTEGER     NOT NULL,
  flow_no     INTEGER     NOT NULL,
  slot_key    VARCHAR(40) NOT NULL,
  ord         INTEGER     NOT NULL,
  value       VARCHAR(60) NOT NULL,
  PRIMARY KEY (judgment_id, flow_no, slot_key, ord),
  FOREIGN KEY (judgment_id, flow_no, slot_key) REFERENCES case_judgment_slots(judgment_id, flow_no, slot_key),
  FOREIGN KEY (slot_key, value) REFERENCES slot_options(slot_key, value)
);

-- [하위 테이블] 쟁점(흐름별). 합친 쟁점·사안 등급은 이 행에서 계산된다(쟁점 합침, 등급 최고)
CREATE TABLE case_judgment_issues (
  issue_row_id INTEGER     NOT NULL PRIMARY KEY,
  judgment_id  INTEGER     NOT NULL REFERENCES case_judgments(judgment_id),
  flow_no      INTEGER     NOT NULL,
  issue_name   VARCHAR(50) NOT NULL,                -- issue_packages.issue_name(그 꾸러미 판)
  package_id   VARCHAR(10) NOT NULL,                -- PKG01~PKG14
  grade        CHAR(1)     NOT NULL CHECK (grade IN ('H','M','L')),
  rule_ids     TEXT        NOT NULL CHECK (json_valid(rule_ids)),       -- 걸린 현업 규칙(R-…)
  conditions   TEXT        NOT NULL CHECK (json_valid(conditions)),     -- 연 세부 조건 ID(C1, C7 …)
  interp_flags TEXT        NOT NULL CHECK (json_valid(interp_flags)),   -- 켠 해석 쟁점(그 판에서 '해석 쟁점'인 I…만)
  conclusive   INTEGER     NOT NULL CHECK (conclusive IN (0,1)),
  opinion      TEXT,                                -- A5 의견(개조식)
  actions      TEXT,
  visible_to_requester INTEGER NOT NULL CHECK (visible_to_requester IN (0,1)),
  UNIQUE (judgment_id, flow_no, issue_name)
);

-- [하위 테이블] 인용. 판정 하나에 여러 개이고, 법령·처리방침 개정 때 키로 영향받은 판정을 찾아야 해서 행으로 둔다(A8)
CREATE TABLE case_judgment_citations (
  cite_row_id    INTEGER      NOT NULL PRIMARY KEY,
  judgment_id    INTEGER      NOT NULL REFERENCES case_judgments(judgment_id),
  issue_row_id   INTEGER      REFERENCES case_judgment_issues(issue_row_id),  -- NULL = 판정 전체 근거(원장 행 등)
  ord            INTEGER      NOT NULL,
  cite_kind      VARCHAR(14)  NOT NULL CHECK (cite_kind IN ('LAW_UNIT','ANNEX','GUIDE_SECTION','LEDGER_ROW')),
  cite_key       VARCHAR(120) NOT NULL,             -- unit_key / annex_key / section_id / ledger row_id
  text_hash      CHAR(64)     NOT NULL,             -- 인용한 그 판의 해시(아래 store.py 정의). 키와 짝이 DB에 있어야 함(C13)
  ledger_version VARCHAR(20),                       -- LEDGER_ROW만
  origin         VARCHAR(10)  NOT NULL CHECK (origin IN ('PACKAGE','A6','LEDGER')), -- 꾸러미 / 보조 검색 / A3 원장 후보
  UNIQUE (judgment_id, ord),
  CHECK ((cite_kind = 'LEDGER_ROW') = (ledger_version IS NOT NULL))
);
CREATE INDEX idx_case_cite_key ON case_judgment_citations (cite_kind, cite_key);
