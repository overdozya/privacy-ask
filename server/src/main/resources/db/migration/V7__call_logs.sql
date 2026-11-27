-- V7 호출·턴 기록(06 4.4·6.4). llm_call_logs = LLM 시도 하나에 한 행(pa/llm이 씀). turn_logs = 턴 하나에 한 행(S7이 씀).
-- 요청 글은 남기지 않는다(프롬프트 판 + steps.input_json으로 다시 만들고 input_sha256으로 같은지 확인). 응답 글은 남긴다. 키는 어디에도 남기지 않는다.
-- case_id·step_seq는 단계 행보다 먼저 쓰이므로 FK를 걸지 않는다. NULL = 기동 확인 호출(S0_ping) 또는 버린 추측 호출(discarded=1).

CREATE TABLE llm_call_logs (
  call_id        BIGINT       NOT NULL AUTO_INCREMENT,
  case_id        CHAR(36),
  step_seq       INT,
  prompt_id      VARCHAR(40)  NOT NULL,
  prompt_version VARCHAR(20)  NOT NULL,
  provider       VARCHAR(20)  NOT NULL,
  model          VARCHAR(60)  NOT NULL,
  model_version  VARCHAR(100),
  temperature    DECIMAL(3,2),
  attempt_no     INT          NOT NULL,
  outcome        VARCHAR(12)  NOT NULL,
  http_status    INT,
  finish_reason  VARCHAR(40),
  latency_ms     INT,
  prompt_tokens  INT,
  output_tokens  INT,
  thought_tokens INT,
  input_sha256   CHAR(64)     NOT NULL,
  response_text  MEDIUMTEXT,
  error          TEXT,
  discarded      TINYINT(1)   NOT NULL DEFAULT 0,
  at             DATETIME(3)  NOT NULL,
  PRIMARY KEY (call_id),
  KEY idx_llm_call_logs_step (case_id, step_seq),
  KEY idx_llm_call_logs_prompt (prompt_id, at),
  CONSTRAINT ck_llm_call_logs_attempt CHECK (attempt_no IN (1,2,3)),
  CONSTRAINT ck_llm_call_logs_outcome CHECK (outcome IN ('OK','SCHEMA_FAIL','CHECK_FAIL','FINISH_FAIL','HTTP_ERROR','TIMEOUT')),
  CONSTRAINT ck_llm_call_logs_version CHECK (REGEXP_LIKE(prompt_version, '^[^@[:space:]]+@[0-9a-f]{8}$', 'c'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='LLM 호출 시도';

CREATE TABLE turn_logs (
  turn_log_id    BIGINT       NOT NULL AUTO_INCREMENT,
  case_id        CHAR(36)     NOT NULL,
  turn_no        INT,
  request_kind   VARCHAR(20)  NOT NULL COMMENT 'CREATE / ANSWER / RETRY',
  total_ms       INT          NOT NULL,
  llm_ms         INT          NOT NULL DEFAULT 0,
  llm_calls      INT          NOT NULL DEFAULT 0,
  llm_depth      INT          NOT NULL DEFAULT 0 COMMENT '직렬 LLM 깊이',
  db_statements  INT,
  action_path    JSON         COMMENT 'NextAction 경로',
  result_code    VARCHAR(30)  NOT NULL,
  at             DATETIME(3)  NOT NULL,
  PRIMARY KEY (turn_log_id),
  KEY idx_turn_logs_case (case_id, turn_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='턴 기록(06 6.4)';
