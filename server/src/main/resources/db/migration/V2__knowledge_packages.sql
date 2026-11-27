-- V2 지식: 쟁점 꾸러미(D6, v1·v2). 채우는 것: data/out/mysql/20_packages.sql
-- package_* → kb_* FK는 걸지 않는다(06 2.2, S1 4-4: 10_kb.sql 재적재가 막힘). 기동 때 스냅샷이 확인한다.

CREATE TABLE issue_packages (
  version         VARCHAR(10)  COLLATE utf8mb4_bin NOT NULL,
  package_id      VARCHAR(10)  COLLATE utf8mb4_bin NOT NULL,
  is_current      TINYINT(1)   NOT NULL,
  issue_name      VARCHAR(50)  NOT NULL,
  origin          VARCHAR(40)  NOT NULL,
  trigger_json    JSON         NOT NULL,
  trigger_status  VARCHAR(10)  NOT NULL,
  opened_by       JSON,
  trigger_note    TEXT,
  rules_json      JSON         NOT NULL,
  conditions_json JSON         NOT NULL,
  excluded_json   JSON         NOT NULL,
  item_count      INT          NOT NULL,
  unit_count      INT          NOT NULL,
  law_chars       INT          NOT NULL,
  annex_chars     INT          NOT NULL,
  guide_chars     INT          NOT NULL,
  total_chars     INT          NOT NULL,
  basis_chars     INT          NOT NULL,
  input_sha256    CHAR(64)     NOT NULL,
  status          VARCHAR(20)  NOT NULL,
  decided_by      VARCHAR(20),
  decided_at      DATE,
  content_sha256  CHAR(64)     NOT NULL,
  PRIMARY KEY (version, package_id),
  UNIQUE KEY uk_issue_packages_name (version, issue_name),
  CONSTRAINT ck_issue_packages_trigger CHECK (trigger_status IN ('현업','제안','사실 조건'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='쟁점 꾸러미';

CREATE TABLE package_items (
  item_id       INT          NOT NULL,
  version       VARCHAR(10)  COLLATE utf8mb4_bin NOT NULL,
  package_id    VARCHAR(10)  COLLATE utf8mb4_bin NOT NULL,
  ord           INT          NOT NULL,
  target_kind   VARCHAR(20)  NOT NULL,
  target_key    VARCHAR(100) COLLATE utf8mb4_bin NOT NULL,
  target_doc_id INT          NOT NULL,
  role          VARCHAR(5)   NOT NULL,
  reason        TEXT         NOT NULL,
  source        VARCHAR(20)  NOT NULL,
  conds         JSON,
  answer_basis  TINYINT(1)   NOT NULL,
  basis_note    TEXT,
  unit_count    INT          NOT NULL,
  char_count    INT          NOT NULL,
  PRIMARY KEY (item_id),
  UNIQUE KEY uk_package_items_target (version, package_id, target_kind, target_key),
  KEY idx_pkg_item (version, package_id, ord),
  CONSTRAINT ck_package_items_role CHECK (role IN ('핵심','요건','예외','절차','해석')),
  CONSTRAINT fk_package_items_package FOREIGN KEY (version, package_id) REFERENCES issue_packages (version, package_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='꾸러미 항목';

CREATE TABLE package_item_units (
  item_id   INT          NOT NULL,
  ord       INT          NOT NULL,
  unit_key  VARCHAR(100) COLLATE utf8mb4_bin NOT NULL,
  chunk_id  INT,
  annex_id  INT,
  PRIMARY KEY (item_id, ord),
  KEY idx_pkg_unit_chunk (chunk_id),
  CONSTRAINT fk_package_item_units_item FOREIGN KEY (item_id) REFERENCES package_items (item_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='꾸러미 항목의 원문 단위';
