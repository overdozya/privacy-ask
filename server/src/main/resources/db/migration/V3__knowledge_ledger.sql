-- V3 지식: 처리 원장(D8, 처리방침 첨부 1~10). 채우는 것: data/out/mysql/30_ledger.sql
-- fields_json은 원장 인용 해시 대상이라 글 그대로 MEDIUMTEXT + JSON_VALID(06 2.2). 나머지 JSON 글은 JSON 형.

CREATE TABLE ledger_versions (
  version_id   VARCHAR(20)  COLLATE utf8mb4_bin NOT NULL,
  published_on DATE         NOT NULL,
  attachment   VARCHAR(10)  COLLATE utf8mb4_bin NOT NULL,
  title        VARCHAR(200) NOT NULL,
  file_name    VARCHAR(300) NOT NULL,
  file_sha256  CHAR(64)     NOT NULL,
  page_count   INT          NOT NULL,
  extractor    VARCHAR(40)  NOT NULL,
  PRIMARY KEY (version_id, attachment)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='처리방침 판 × 첨부';

CREATE TABLE ledger_rows (
  row_id         VARCHAR(20)  COLLATE utf8mb4_bin NOT NULL,
  version_id     VARCHAR(20)  COLLATE utf8mb4_bin NOT NULL,
  attachment     VARCHAR(10)  COLLATE utf8mb4_bin NOT NULL,
  seq            INT          NOT NULL,
  table_no       INT          NOT NULL,
  table_title    VARCHAR(200) NOT NULL,
  service        VARCHAR(500) NOT NULL,
  service_key    VARCHAR(500) COLLATE utf8mb4_bin NOT NULL,
  fields_json    MEDIUMTEXT   NOT NULL,
  col_kinds_json JSON         NOT NULL,
  pages_json     JSON         NOT NULL,
  first_page     INT          NOT NULL,
  merged_json    JSON         NOT NULL,
  flags_json     JSON         NOT NULL,
  PRIMARY KEY (row_id),
  KEY ledger_rows_att (attachment, seq),
  KEY ledger_rows_service (service_key),
  CONSTRAINT ck_ledger_rows_fields CHECK (JSON_VALID(fields_json)),
  CONSTRAINT fk_ledger_rows_version FOREIGN KEY (version_id, attachment) REFERENCES ledger_versions (version_id, attachment)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='처리 원장 행';

CREATE TABLE ledger_segments (
  segment_id VARCHAR(40)  COLLATE utf8mb4_bin NOT NULL,
  row_id     VARCHAR(20)  COLLATE utf8mb4_bin NOT NULL,
  col_name   VARCHAR(100) NOT NULL,
  col_kind   VARCHAR(20)  NOT NULL,
  seg_type   VARCHAR(20)  NOT NULL,
  seq        INT          NOT NULL,
  parent_id  VARCHAR(40)  COLLATE utf8mb4_bin,
  term       VARCHAR(100),
  source_id  VARCHAR(40)  COLLATE utf8mb4_bin,
  text       TEXT         NOT NULL,
  PRIMARY KEY (segment_id),
  KEY ledger_segments_row (row_id),
  KEY ledger_segments_kind (col_kind),
  CONSTRAINT fk_ledger_segments_row FOREIGN KEY (row_id) REFERENCES ledger_rows (row_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='원장 행 조각';

CREATE TABLE ledger_unextracted (
  attachment VARCHAR(10)  COLLATE utf8mb4_bin NOT NULL,
  page       INT          NOT NULL,
  kind       VARCHAR(30)  NOT NULL,
  ref        VARCHAR(40)  NOT NULL,
  text       TEXT         NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='행으로 못 넣은 것';
