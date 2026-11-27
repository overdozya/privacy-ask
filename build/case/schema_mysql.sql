-- 사안 기록(D9) MySQL 8 판(InnoDB, utf8mb4). schema.sql(SQLite)과 테이블·칸 이름·순서가 같다(tests/test_case.py가 확인).
-- 실행 확인은 S2(로컬 MySQL)가 한다. SQLite 판과 다른 곳만 [다름] 주석. 판정 테이블 5개와 제약·색인 이름은 팀 V1과 겹치지 않게 case_ 접두.
--   [다름] 공통: 날짜 VARCHAR(10) → DATE, 시각 VARCHAR(23) → DATETIME(3)(Asia/Seoul), JSON 글 + json_valid → JSON 형,
--          0/1 INTEGER → TINYINT(1), REAL → DECIMAL(3,2), 자동 번호 INTEGER PRIMARY KEY → BIGINT AUTO_INCREMENT,
--          PRAGMA foreign_keys 없음(InnoDB는 늘 FK 검사), CHECK는 8.0.16부터 실제로 검사됨.
-- 다른 DB를 봐야 하는 거부 규칙(C03·C06·C11·C13·C15 등)은 칸 제약으로 안 된다. 지식 쪽(kb_chunks 등)이 같은 스키마에 있어도
--   kb_chunks.unit_key는 유일하지 않아(현행·예정본) FK를 걸 수 없다. 저장 함수(Java)가 case/store.py와 같은 검사를 한다.
-- 기준표(catalog_sources, fact_catalog, fact_options, slot_catalog, slot_options)는 store.py가 tsv·seed에서 읽어 넣는 것과
--   같은 행을 넣는다(예: SQLite case.db의 기준표 행을 INSERT 문으로 옮김).

SET NAMES utf8mb4;

-- ───────── 기준표 ─────────

CREATE TABLE catalog_sources (
  name        VARCHAR(40)  NOT NULL,
  path        VARCHAR(300) NOT NULL,
  version     VARCHAR(40)  NOT NULL,
  sha256      CHAR(64)     NOT NULL,
  PRIMARY KEY (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='기준 파일과 해시';

CREATE TABLE fact_catalog (
  fact_id       VARCHAR(10)  NOT NULL,
  ord           INT          NOT NULL,
  slot          VARCHAR(60)  NOT NULL,
  is_common     TINYINT(1)   NOT NULL,
  fact_usage    VARCHAR(40)  NOT NULL COMMENT '쓰임(USAGE는 MySQL 예약어라 fact_usage)',
  question      TEXT         NOT NULL,
  allowed_raw   TEXT         NOT NULL,
  is_multi      TINYINT(1)   NOT NULL,
  ledger_lookup TEXT         NOT NULL,
  ledger_attachments JSON    NOT NULL,
  on_unknown    TEXT         NOT NULL,
  basis_keys    TEXT         NOT NULL,
  interp        VARCHAR(40)  NOT NULL,
  PRIMARY KEY (fact_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='04 대응표 사실';

CREATE TABLE fact_options (
  fact_id     VARCHAR(10)  NOT NULL,
  ord         INT          NOT NULL,
  label       VARCHAR(120) COLLATE utf8mb4_bin NOT NULL,
  kind        VARCHAR(10)  NOT NULL,
  PRIMARY KEY (fact_id, label),
  CONSTRAINT ck_fact_options_kind CHECK (kind IN ('CHOICE','DETAIL','UNKNOWN','MATCH','NO_MATCH')),
  CONSTRAINT fk_fact_options_fact FOREIGN KEY (fact_id) REFERENCES fact_catalog (fact_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='사실 허용 값';
-- [다름] 허용 값 칸(fact_options.label, fact_values.label, slot_options.value, case_judgment_slot_values.value)은 COLLATE utf8mb4_bin:
--        SQLite의 이진 비교와 같게 글자 그대로 같아야 같은 값이다(06 2.3). FK로 묶인 두 칸은 같은 collation이어야 한다.

CREATE TABLE slot_catalog (
  slot_key         VARCHAR(40) NOT NULL,
  label            VARCHAR(40) NOT NULL,
  value_type       VARCHAR(10) NOT NULL,
  ask_condition    JSON,
  rule_set_version VARCHAR(10) NOT NULL,
  sort_order       INT         NOT NULL,
  PRIMARY KEY (slot_key),
  CONSTRAINT ck_slot_catalog_type CHECK (value_type IN ('SINGLE','MULTI'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='현업 슬롯 8개';

CREATE TABLE slot_options (
  slot_key  VARCHAR(40) NOT NULL,
  ord       INT         NOT NULL,
  value     VARCHAR(60) COLLATE utf8mb4_bin NOT NULL,
  PRIMARY KEY (slot_key, value),
  CONSTRAINT fk_slot_options_slot FOREIGN KEY (slot_key) REFERENCES slot_catalog (slot_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='슬롯 허용 값';

-- ───────── 사안 기록 ─────────

CREATE TABLE cases (
  case_id        CHAR(36)     NOT NULL,
  requester_id   VARCHAR(20)  NOT NULL,
  content        TEXT         NOT NULL,
  service_name   VARCHAR(200),
  intake_type    VARCHAR(12),
  out_of_scope_kind VARCHAR(20),
  state          VARCHAR(12)  NOT NULL,
  rule_set_version VARCHAR(10) NOT NULL,
  package_version  VARCHAR(10) NOT NULL,
  ledger_version   VARCHAR(20),
  reference_date DATE,
  reference_date_basis VARCHAR(10),
  created_at     DATETIME(3)  NOT NULL,
  updated_at     DATETIME(3)  NOT NULL,
  closed_at      DATETIME(3),
  PRIMARY KEY (case_id),
  CONSTRAINT ck_cases_intake CHECK (intake_type IS NULL OR intake_type IN ('GENERAL','SPECIFIC','OUT_OF_SCOPE')),
  CONSTRAINT ck_cases_oos CHECK (out_of_scope_kind IS NULL OR out_of_scope_kind IN ('BREACH','DATA_SUBJECT_REQUEST','OUTSIDE_KB_LAW')),
  CONSTRAINT ck_cases_state CHECK (state IN ('ASKING','JUDGED','TRANSFERRED')),
  CONSTRAINT ck_cases_refbasis CHECK (reference_date_basis IS NULL OR reference_date_basis IN ('INQUIRY','TODAY')),
  CONSTRAINT ck_cases_oos_only CHECK (intake_type = 'OUT_OF_SCOPE' OR out_of_scope_kind IS NULL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='사안';

CREATE TABLE steps (
  case_id        CHAR(36)     NOT NULL,
  seq            INT          NOT NULL,
  step           VARCHAR(2)   NOT NULL,
  flow_no        INT,
  actor          VARCHAR(5)   NOT NULL,
  name           VARCHAR(60)  NOT NULL,
  prompt_id      VARCHAR(40),
  prompt_version VARCHAR(20),
  model_name     VARCHAR(60),
  temperature    DECIMAL(3,2),
  code_version   VARCHAR(40),
  input_json     JSON         NOT NULL,
  output_json    JSON         NOT NULL,
  status         VARCHAR(12)  NOT NULL,
  attempts       INT          NOT NULL DEFAULT 1,
  at             DATETIME(3)  NOT NULL,
  PRIMARY KEY (case_id, seq),
  CONSTRAINT ck_steps_step CHECK (step IN ('A0','A1','A2','A3','A4','A5','A6','A7','A8')),
  CONSTRAINT ck_steps_actor CHECK (actor IN ('LLM','CODE','TOOL')),
  CONSTRAINT ck_steps_status CHECK (status IN ('OK','RETRY_OK','LLM_FAILED')),
  CONSTRAINT ck_steps_attempts CHECK (attempts IN (1,2,3)),
  CONSTRAINT ck_steps_llm CHECK (actor <> 'LLM' OR (prompt_id IS NOT NULL AND prompt_version IS NOT NULL AND model_name IS NOT NULL AND temperature IS NOT NULL)),
  CONSTRAINT ck_steps_status_llm CHECK (status = 'OK' OR actor = 'LLM'),
  CONSTRAINT fk_steps_case FOREIGN KEY (case_id) REFERENCES cases (case_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='단계 기록(A0~A8 입력·출력)';

CREATE TABLE flows (
  case_id      CHAR(36)     NOT NULL,
  flow_no      INT          NOT NULL,
  label        VARCHAR(200) NOT NULL,
  origin       VARCHAR(200) NOT NULL,
  destination  VARCHAR(200) NOT NULL,
  service_name VARCHAR(200),
  created_step INT          NOT NULL,
  PRIMARY KEY (case_id, flow_no),
  CONSTRAINT ck_flows_no CHECK (flow_no >= 1),
  CONSTRAINT fk_flows_case FOREIGN KEY (case_id) REFERENCES cases (case_id),
  CONSTRAINT fk_flows_step FOREIGN KEY (case_id, created_step) REFERENCES steps (case_id, seq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='처리 흐름';

CREATE TABLE turns (
  case_id      CHAR(36)     NOT NULL,
  turn_no      INT          NOT NULL,
  role         VARCHAR(5)   NOT NULL,
  kind         VARCHAR(10)  NOT NULL,
  flow_no      INT,
  fact_id      VARCHAR(10),
  ask_no       INT,
  reply_to     INT,
  text         TEXT         NOT NULL,
  choices_json JSON         NOT NULL,
  step_seq     INT          NOT NULL,
  at           DATETIME(3)  NOT NULL,
  PRIMARY KEY (case_id, turn_no),
  CONSTRAINT ck_turns_no CHECK (turn_no >= 1),
  CONSTRAINT ck_turns_role CHECK (role IN ('AGENT','USER')),
  CONSTRAINT ck_turns_kind CHECK (kind IN ('QUESTION','ANSWER')),
  CONSTRAINT ck_turns_ask CHECK (ask_no IS NULL OR ask_no IN (1,2)),
  CONSTRAINT ck_turns_q CHECK (kind <> 'QUESTION' OR (role = 'AGENT' AND fact_id IS NOT NULL AND ask_no IS NOT NULL AND reply_to IS NULL)),
  CONSTRAINT ck_turns_a CHECK (kind <> 'ANSWER' OR (role = 'USER' AND reply_to IS NOT NULL)),
  CONSTRAINT fk_turns_case FOREIGN KEY (case_id) REFERENCES cases (case_id),
  CONSTRAINT fk_turns_fact FOREIGN KEY (fact_id) REFERENCES fact_catalog (fact_id),
  CONSTRAINT fk_turns_step FOREIGN KEY (case_id, step_seq) REFERENCES steps (case_id, seq),
  CONSTRAINT fk_turns_flow FOREIGN KEY (case_id, flow_no) REFERENCES flows (case_id, flow_no),
  CONSTRAINT fk_turns_reply FOREIGN KEY (case_id, reply_to) REFERENCES turns (case_id, turn_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='질문과 답';

CREATE TABLE facts (
  fact_row_id   BIGINT       NOT NULL AUTO_INCREMENT,
  case_id       CHAR(36)     NOT NULL,
  flow_no       INT,
  fact_id       VARCHAR(10)  NOT NULL,
  rev           INT          NOT NULL,
  status        VARCHAR(12)  NOT NULL,
  source_kind   VARCHAR(8)   NOT NULL,
  source_turn   INT,
  source_step   INT          NOT NULL,
  basis_quote   TEXT,
  ledger_version VARCHAR(20),
  note          TEXT,
  recorded_at   DATETIME(3)  NOT NULL,
  PRIMARY KEY (fact_row_id),
  UNIQUE KEY uk_facts_rev (case_id, flow_no, fact_id, rev),
  UNIQUE KEY uk_facts_row_fact (fact_row_id, fact_id),
  KEY idx_facts_case (case_id, flow_no, fact_id),
  CONSTRAINT ck_facts_rev CHECK (rev >= 1),
  CONSTRAINT ck_facts_status CHECK (status IN ('CONFIRMED','MATCHED','INFERRED','NOT_FOUND','UNKNOWN')),
  CONSTRAINT ck_facts_source CHECK (source_kind IN ('INQUIRY','ANSWER','LEDGER')),
  CONSTRAINT ck_facts_answer CHECK (source_kind <> 'ANSWER' OR source_turn IS NOT NULL),
  CONSTRAINT ck_facts_ledger CHECK (source_kind <> 'LEDGER' OR ledger_version IS NOT NULL),
  CONSTRAINT ck_facts_ledger2 CHECK (source_kind = 'LEDGER' OR ledger_version IS NULL),
  CONSTRAINT fk_facts_case FOREIGN KEY (case_id) REFERENCES cases (case_id),
  CONSTRAINT fk_facts_fact FOREIGN KEY (fact_id) REFERENCES fact_catalog (fact_id),
  CONSTRAINT fk_facts_flow FOREIGN KEY (case_id, flow_no) REFERENCES flows (case_id, flow_no),
  CONSTRAINT fk_facts_turn FOREIGN KEY (case_id, source_turn) REFERENCES turns (case_id, turn_no),
  CONSTRAINT fk_facts_step FOREIGN KEY (case_id, source_step) REFERENCES steps (case_id, seq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='흐름별 사실(판을 쌓음)';

CREATE TABLE fact_values (
  fact_row_id BIGINT       NOT NULL,
  fact_id     VARCHAR(10)  NOT NULL,
  ord         INT          NOT NULL,
  label       VARCHAR(120) COLLATE utf8mb4_bin NOT NULL,
  detail      TEXT,
  PRIMARY KEY (fact_row_id, ord),
  CONSTRAINT fk_fact_values_row FOREIGN KEY (fact_row_id, fact_id) REFERENCES facts (fact_row_id, fact_id),
  CONSTRAINT fk_fact_values_option FOREIGN KEY (fact_id, label) REFERENCES fact_options (fact_id, label)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='사실 값(허용 값 FK)';

CREATE TABLE fact_ledger_rows (
  fact_row_id BIGINT      NOT NULL,
  ord         INT         NOT NULL,
  row_id      VARCHAR(20) NOT NULL,
  PRIMARY KEY (fact_row_id, ord),
  KEY idx_fact_ledger_row (row_id),
  CONSTRAINT fk_fact_ledger_rows_fact FOREIGN KEY (fact_row_id) REFERENCES facts (fact_row_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='원장 출처 사실의 처리 원장 행';

CREATE VIEW facts_current AS
SELECT f.* FROM facts f
WHERE NOT EXISTS (
  SELECT 1 FROM facts g
  WHERE g.case_id = f.case_id AND g.fact_id = f.fact_id AND g.rev > f.rev
    AND (g.flow_no = f.flow_no OR (g.flow_no IS NULL AND f.flow_no IS NULL)));

CREATE TABLE case_judgments (
  judgment_id      BIGINT       NOT NULL AUTO_INCREMENT,
  case_id          CHAR(36)     NOT NULL,
  step_seq         INT          NOT NULL,
  judged_turn      INT,
  reference_date   DATE         NOT NULL,
  rule_set_version VARCHAR(10)  NOT NULL,
  package_version  VARCHAR(10)  NOT NULL,
  ledger_version   VARCHAR(20),
  fact_map_sha256  CHAR(64)     NOT NULL,
  model_name       VARCHAR(60)  NOT NULL,
  temperature      DECIMAL(3,2) NOT NULL,
  prompt_versions  JSON         NOT NULL,
  case_grade       CHAR(1),
  exit_type        VARCHAR(10)  NOT NULL,
  exit_reasons     JSON         NOT NULL,
  conclusive       TINYINT(1)   NOT NULL,
  flags            JSON         NOT NULL,
  review_bundle    JSON,
  premise_note     VARCHAR(200) NOT NULL,
  created_at       DATETIME(3)  NOT NULL,
  PRIMARY KEY (judgment_id),
  UNIQUE KEY uk_case_judgments_case (judgment_id, case_id),
  CONSTRAINT ck_case_judgments_grade CHECK (case_grade IS NULL OR case_grade IN ('H','M','L')),
  CONSTRAINT ck_case_judgments_exit CHECK (exit_type IN ('ANSWER','GUIDE','TRANSFER')),
  CONSTRAINT ck_case_judgments_bundle CHECK (exit_type <> 'TRANSFER' OR review_bundle IS NOT NULL),
  CONSTRAINT fk_case_judgments_case FOREIGN KEY (case_id) REFERENCES cases (case_id),
  CONSTRAINT fk_case_judgments_step FOREIGN KEY (case_id, step_seq) REFERENCES steps (case_id, seq),
  CONSTRAINT fk_case_judgments_turn FOREIGN KEY (case_id, judged_turn) REFERENCES turns (case_id, turn_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='판정(A8 기록)';

CREATE TABLE case_judgment_slots (
  judgment_id  BIGINT      NOT NULL,
  case_id      CHAR(36)    NOT NULL,
  flow_no      INT         NOT NULL,
  slot_key     VARCHAR(40) NOT NULL,
  status       VARCHAR(10) NOT NULL,
  basis_facts  JSON        NOT NULL,
  unknown_facts JSON       NOT NULL,
  PRIMARY KEY (judgment_id, flow_no, slot_key),
  CONSTRAINT ck_case_judgment_slots_status CHECK (status IN ('CONFIRMED','PARTIAL','UNKNOWN','SKIPPED')),
  CONSTRAINT fk_case_judgment_slots_slot FOREIGN KEY (slot_key) REFERENCES slot_catalog (slot_key),
  CONSTRAINT fk_case_judgment_slots_judgment FOREIGN KEY (judgment_id, case_id) REFERENCES case_judgments (judgment_id, case_id),
  CONSTRAINT fk_case_judgment_slots_flow FOREIGN KEY (case_id, flow_no) REFERENCES flows (case_id, flow_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='흐름별 슬롯 상태';

CREATE TABLE case_judgment_slot_values (
  judgment_id BIGINT      NOT NULL,
  flow_no     INT         NOT NULL,
  slot_key    VARCHAR(40) NOT NULL,
  ord         INT         NOT NULL,
  value       VARCHAR(60) COLLATE utf8mb4_bin NOT NULL,
  PRIMARY KEY (judgment_id, flow_no, slot_key, ord),
  CONSTRAINT fk_case_judgment_slot_values_slot FOREIGN KEY (judgment_id, flow_no, slot_key) REFERENCES case_judgment_slots (judgment_id, flow_no, slot_key),
  CONSTRAINT fk_case_judgment_slot_values_option FOREIGN KEY (slot_key, value) REFERENCES slot_options (slot_key, value)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='슬롯 값(허용 값 FK)';

CREATE TABLE case_judgment_issues (
  issue_row_id BIGINT      NOT NULL AUTO_INCREMENT,
  judgment_id  BIGINT      NOT NULL,
  flow_no      INT         NOT NULL,
  issue_name   VARCHAR(50) NOT NULL,
  package_id   VARCHAR(10) NOT NULL,
  grade        CHAR(1)     NOT NULL,
  rule_ids     JSON        NOT NULL,
  conditions   JSON        NOT NULL,
  interp_flags JSON        NOT NULL,
  conclusive   TINYINT(1)  NOT NULL,
  opinion      TEXT,
  actions      TEXT,
  visible_to_requester TINYINT(1) NOT NULL,
  PRIMARY KEY (issue_row_id),
  UNIQUE KEY uk_case_judgment_issues (judgment_id, flow_no, issue_name),
  CONSTRAINT ck_case_judgment_issues_grade CHECK (grade IN ('H','M','L')),
  CONSTRAINT fk_case_judgment_issues_judgment FOREIGN KEY (judgment_id) REFERENCES case_judgments (judgment_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='흐름별 쟁점';

CREATE TABLE case_judgment_citations (
  cite_row_id    BIGINT       NOT NULL AUTO_INCREMENT,
  judgment_id    BIGINT       NOT NULL,
  issue_row_id   BIGINT,
  ord            INT          NOT NULL,
  cite_kind      VARCHAR(14)  NOT NULL,
  cite_key       VARCHAR(120) NOT NULL,
  text_hash      CHAR(64)     NOT NULL,
  ledger_version VARCHAR(20),
  origin         VARCHAR(10)  NOT NULL,
  PRIMARY KEY (cite_row_id),
  UNIQUE KEY uk_case_judgment_citations (judgment_id, ord),
  KEY idx_case_cite_key (cite_kind, cite_key),
  CONSTRAINT ck_case_cite_kind CHECK (cite_kind IN ('LAW_UNIT','ANNEX','GUIDE_SECTION','LEDGER_ROW')),
  CONSTRAINT ck_case_cite_origin CHECK (origin IN ('PACKAGE','A6','LEDGER')),
  CONSTRAINT ck_case_cite_ledger CHECK ((cite_kind = 'LEDGER_ROW') = (ledger_version IS NOT NULL)),
  CONSTRAINT fk_case_cite_judgment FOREIGN KEY (judgment_id) REFERENCES case_judgments (judgment_id),
  CONSTRAINT fk_case_cite_issue FOREIGN KEY (issue_row_id) REFERENCES case_judgment_issues (issue_row_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='인용(키 + 해시)';
