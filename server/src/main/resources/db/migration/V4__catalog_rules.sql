-- V4 기준표(D9 기준표 5개) + 규칙 테이블 3개(06 2.4). 채우는 것: data/out/mysql/40_case_catalog.sql
-- 기준표는 build/case/schema_mysql.sql(4b·S1)과 칸 이름·순서가 같다. 다른 곳: 키·허용 값 칸 utf8mb4_bin(06 2.2).
-- 규칙 3테이블(rule_sets, risk_rules, slot_rules)은 S3가 40번 적재에 더한다(현업 rule_set_v1.0.json, data/수집/슬롯변환_v1.json).

CREATE TABLE catalog_sources (
  name        VARCHAR(40)  COLLATE utf8mb4_bin NOT NULL,
  path        VARCHAR(300) NOT NULL,
  version     VARCHAR(40)  NOT NULL,
  sha256      CHAR(64)     NOT NULL,
  PRIMARY KEY (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='기준 파일과 해시(rule_set, fact_map, slot_rules)';

CREATE TABLE fact_catalog (
  fact_id       VARCHAR(10)  COLLATE utf8mb4_bin NOT NULL,
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
  fact_id     VARCHAR(10)  COLLATE utf8mb4_bin NOT NULL,
  ord         INT          NOT NULL,
  label       VARCHAR(120) COLLATE utf8mb4_bin NOT NULL,
  kind        VARCHAR(10)  NOT NULL,
  PRIMARY KEY (fact_id, label),
  CONSTRAINT ck_fact_options_kind CHECK (kind IN ('CHOICE','DETAIL','UNKNOWN','MATCH','NO_MATCH')),
  CONSTRAINT fk_fact_options_fact FOREIGN KEY (fact_id) REFERENCES fact_catalog (fact_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='사실 허용 값';

CREATE TABLE slot_catalog (
  slot_key         VARCHAR(40) COLLATE utf8mb4_bin NOT NULL,
  label            VARCHAR(40) NOT NULL,
  value_type       VARCHAR(10) NOT NULL,
  ask_condition    JSON,
  rule_set_version VARCHAR(10) COLLATE utf8mb4_bin NOT NULL,
  sort_order       INT         NOT NULL,
  PRIMARY KEY (slot_key),
  CONSTRAINT ck_slot_catalog_type CHECK (value_type IN ('SINGLE','MULTI'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='현업 슬롯 8개';

CREATE TABLE slot_options (
  slot_key  VARCHAR(40) COLLATE utf8mb4_bin NOT NULL,
  ord       INT         NOT NULL,
  value     VARCHAR(60) COLLATE utf8mb4_bin NOT NULL,
  PRIMARY KEY (slot_key, value),
  CONSTRAINT fk_slot_options_slot FOREIGN KEY (slot_key) REFERENCES slot_catalog (slot_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='슬롯 허용 값';

-- ───────── 규칙 (06 2.4) ─────────

CREATE TABLE rule_sets (
  version       VARCHAR(10) COLLATE utf8mb4_bin NOT NULL,
  status        VARCHAR(20) NOT NULL,
  change_reason TEXT,
  exit_policy   JSON        NOT NULL,
  sha256        CHAR(64)    NOT NULL,
  PRIMARY KEY (version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='현업 규칙 세트(판·출구 정책)';

CREATE TABLE risk_rules (
  version        VARCHAR(10)  COLLATE utf8mb4_bin NOT NULL,
  rule_id        VARCHAR(20)  COLLATE utf8mb4_bin NOT NULL,
  name           VARCHAR(200) NOT NULL,
  axis           VARCHAR(40)  NOT NULL,
  condition_json JSON         NOT NULL,
  grade          CHAR(1)      NOT NULL,
  issue_name     VARCHAR(50)  NOT NULL,
  legal_refs     JSON         NOT NULL,
  description    TEXT,
  active         TINYINT(1)   NOT NULL DEFAULT 1,
  PRIMARY KEY (version, rule_id),
  CONSTRAINT ck_risk_rules_grade CHECK (grade IN ('H','M','L')),
  CONSTRAINT fk_risk_rules_set FOREIGN KEY (version) REFERENCES rule_sets (version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='현업 위험 규칙 16';

CREATE TABLE slot_rules (
  version     VARCHAR(10) COLLATE utf8mb4_bin NOT NULL,
  slot_key    VARCHAR(40) COLLATE utf8mb4_bin NOT NULL,
  ord         INT         NOT NULL,
  when_json   JSON        NOT NULL,
  effect      VARCHAR(3)  NOT NULL,
  values_json JSON        NOT NULL,
  flag        VARCHAR(20),
  basis_keys  JSON        NOT NULL,
  note        TEXT,
  PRIMARY KEY (version, slot_key, ord),
  CONSTRAINT ck_slot_rules_effect CHECK (effect IN ('SET','ADD')),
  CONSTRAINT ck_slot_rules_flag CHECK (flag IS NULL OR flag IN ('FACT_CONFLICT','CHECK_ITEM')),
  CONSTRAINT fk_slot_rules_slot FOREIGN KEY (slot_key) REFERENCES slot_catalog (slot_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='슬롯 변환 규칙(04 1.1~1.10, 사실 조합 → 슬롯 값)';
