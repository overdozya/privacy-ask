-- V5 사안 기록(D9): 사안, 단계, 흐름, 턴, 사실(판을 쌓음), 사실 값, 원장 출처. 쓰는 것: 에이전트(pa/record, S4)
-- 원본: build/case/schema_mysql.sql(4b·S1). 칸 이름·순서가 build/case/schema.sql(SQLite)과 같다. 다른 곳(06 2.2·2.3):
--   시각 DATETIME(3)은 UTC(앱 AppClock). 키·허용 값 칸 utf8mb4_bin(FK로 묶인 기준표 칸과 같은 collation).
--   facts: 생성 칸 flow_key = IFNULL(flow_no, 0), UNIQUE(case_id, flow_key, fact_id, rev)(공통 사실 판 중복을 DB가 막음).
--   facts_current 뷰는 flow_key로 비교. cases에 (requester_id, created_at) 색인(목록 API).
--   D9 → 지식(kb_*, ledger_*)은 FK 없음(키 + text_hash로 판을 남김, 4b C13). D9 → 기준표는 FK.

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
  KEY idx_cases_requester (requester_id, created_at),
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
  fact_id      VARCHAR(10)  COLLATE utf8mb4_bin,
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
  fact_id       VARCHAR(10) COLLATE utf8mb4_bin  NOT NULL,
  rev           INT          NOT NULL,
  status        VARCHAR(12)  NOT NULL,
  source_kind   VARCHAR(8)   NOT NULL,
  source_turn   INT,
  source_step   INT          NOT NULL,
  basis_quote   TEXT,
  ledger_version VARCHAR(20),
  note          TEXT,
  recorded_at   DATETIME(3)  NOT NULL,
  flow_key      INT          AS (IFNULL(flow_no, 0)) STORED COMMENT '공통 사실(flow_no NULL) = 0. 유일성용(06 2.3-1)',
  PRIMARY KEY (fact_row_id),
  UNIQUE KEY uk_facts_rev (case_id, flow_key, fact_id, rev),
  UNIQUE KEY uk_facts_row_fact (fact_row_id, fact_id),
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
  fact_id     VARCHAR(10) COLLATE utf8mb4_bin  NOT NULL,
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
  row_id      VARCHAR(20) COLLATE utf8mb4_bin NOT NULL,
  PRIMARY KEY (fact_row_id, ord),
  KEY idx_fact_ledger_row (row_id),
  CONSTRAINT fk_fact_ledger_rows_fact FOREIGN KEY (fact_row_id) REFERENCES facts (fact_row_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='원장 출처 사실의 처리 원장 행';

CREATE VIEW facts_current AS
SELECT f.* FROM facts f
WHERE NOT EXISTS (
  SELECT 1 FROM facts g
  WHERE g.case_id = f.case_id AND g.flow_key = f.flow_key AND g.fact_id = f.fact_id AND g.rev > f.rev);
