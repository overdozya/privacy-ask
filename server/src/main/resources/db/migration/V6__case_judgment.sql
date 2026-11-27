-- V6 판정(A8 기록, S9가 씀): 판정, 흐름별 슬롯·값, 쟁점, 인용. 원본: build/case/schema_mysql.sql(4b·S1). case_ 접두는 S1 그대로.
-- 다른 곳: 시각 UTC, slot_key·value·package_id·cite_key utf8mb4_bin(06 2.2).

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
  slot_key     VARCHAR(40) COLLATE utf8mb4_bin NOT NULL,
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
  slot_key    VARCHAR(40) COLLATE utf8mb4_bin NOT NULL,
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
  package_id   VARCHAR(10) COLLATE utf8mb4_bin NOT NULL,
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
  cite_key       VARCHAR(120) COLLATE utf8mb4_bin NOT NULL,
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
