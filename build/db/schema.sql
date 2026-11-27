-- 개인정보 판단 에이전트 법령 원문 DB (SQLite). 1단계: 법령 25종. 2단계: 안내서 25종(확정 묶음 글 그대로)과 절 목록.
-- kb_documents, kb_chunks는 팀 MySQL 스키마(docs/PrivacyAsk_산출물_현행/.../schema.sql)와 칸 이름·뜻을 맞췄다.
-- 다른 점은 칸마다 [추가] [바꿈]으로 표시했다. MySQL로 옮길 때 [추가] 칸만 ALTER하면 된다.

PRAGMA foreign_keys = ON;

CREATE TABLE kb_documents (
  doc_id            INTEGER PRIMARY KEY,          -- 확정 묶음 doc_id 그대로(법령 1~6, 16~34 / 안내서 7~15, 35~50)
  source_type       TEXT NOT NULL,                -- LAW (고시도 묶음처럼 LAW) / GUIDE
  title             TEXT NOT NULL,                -- 법령 정식 이름
  law_name          TEXT NOT NULL,                -- 인용 앞부분. 법령은 확정 묶음 law_name과 글자까지 같음.
                                                  -- [바꿈] 안내서는 제목(묶음은 NULL, 인용 앞부분 = 제목이라 같은 칸에 둠)
  law_id            TEXT,                         -- 법령 ID(법령) / 행정규칙 ID(고시)
  law_serial_no     TEXT,                         -- 법령일련번호 MST(법령) / 행정규칙일련번호(고시)
  case_no           TEXT,
  promulgation_date TEXT,                         -- 공포일(고시는 발령일)
  effective_date    TEXT,                         -- 현행 시행일
  stage             INTEGER NOT NULL,
  url               TEXT,
  content_hash      TEXT,                         -- 현행 본문 응답 파일 sha256
  sync_source       TEXT NOT NULL DEFAULT 'LAW_API',
  verified          INTEGER NOT NULL DEFAULT 0,   -- 법령은 쓰지 않는다(use_policy로 대신). 칸은 호환용
  last_checked_at   TEXT,                         -- API에서 받은 시각
  created_at        TEXT NOT NULL,                -- 빌드 입력 날짜(재생성해도 같게 하려고 원천 폴더 날짜)
  -- [추가]
  law_kind          TEXT NOT NULL,                -- 법률 / 대통령령 / 고시 / 안내서
  api_target        TEXT,                         -- eflaw / admrul. 안내서는 NULL(묶음에서 옮김)
  promulgation_no   TEXT,                         -- 공포번호(고시는 발령번호)
  parent_doc_id     INTEGER REFERENCES kb_documents(doc_id), -- 시행령→법률, 고시→근거 법률
  use_policy        TEXT CHECK (use_policy IN ('ANSWER_BASIS','REFERENCE','OUT')), -- D5. 입력: 수집/사용정책_결정.json
  use_scope         TEXT,                         -- ANSWER_BASIS에서 쓰는 범위 JSON 목록: 법령 unit_key 접두 / 안내서 section_id. 목록 밖은 즉답 근거 아님
  use_policy_by     TEXT,
  use_policy_at     TEXT,
  use_policy_note   TEXT,
  guide_version     TEXT,                         -- 안내서 발간 판(예: 2026.9.)
  page_count        INTEGER,                      -- 안내서 PDF 쪽 수
  excluded_pages    TEXT                          -- 안내서에서 묶음이 뺀 쪽 [[시작,끝,이유]…] JSON
);

CREATE TABLE kb_chunks (
  chunk_id       INTEGER PRIMARY KEY,             -- 빌드 순서대로. 같은 입력이면 같은 번호
  doc_id         INTEGER NOT NULL REFERENCES kb_documents(doc_id),
  article        TEXT NOT NULL,                   -- 인용 뒷부분(예: 제15조 제1항). 구조 키에서 다시 만들 수 있음. 안내서는 묶음 소제목 그대로
  text           TEXT NOT NULL,                   -- 원문. 조 머리줄 + 항/호 줄, <개정 …> 표시 포함
  embedding      BLOB,                            -- [바꿈] 팀은 NOT NULL. 이번 단계는 비움
  status         TEXT NOT NULL CHECK (status IN ('CURRENT','PENDING','ARCHIVED')),
  effective_from TEXT,
  effective_to   TEXT,                            -- 삭제일(ARCHIVED) 또는 뒤 시행 예정본에서 이 키가 사라지는 날
  replaced_by    INTEGER,
  text_hash      TEXT NOT NULL,                   -- sha256(text)
  verified       INTEGER NOT NULL DEFAULT 0,
  -- [추가] 구조 키
  unit_key       TEXT NOT NULL,                   -- 법령|조[-의]|항|호|목. 예: 개인정보보호법|28-8|1|| / 안내서: G문서|순번 (예: G13|0042)
  jo             INTEGER,                         -- 안내서는 NULL
  jo_sub         INTEGER DEFAULT 0,               -- 제28조의8 → 8. 안내서는 NULL
  hang           INTEGER,                         -- 없으면 NULL
  ho             TEXT,                            -- 키 표기: 3 / 1-2(제1호의2) / 1~7 / 10-2~12. 없으면 NULL
  ho_from        TEXT,                            -- 묶음 첫 호(키 표기)
  ho_to          TEXT,                            -- 묶음 끝 호
  mok            TEXT,                            -- 이번 청크 단위에서는 항상 NULL(목까지 나누지 않음)
  title          TEXT,                            -- 조 제목
  source_file    TEXT NOT NULL,                   -- 원천 응답 파일(구현/ 기준 상대 경로) / 안내서: 묶음 파일#줄
  -- [추가] 안내서
  chunk_key      TEXT,                            -- 확정 묶음 chunk_key 그대로(팀 DB와 잇는 키)
  section_id     TEXT REFERENCES guide_sections(section_id), -- 이 청크가 속한 잎 절
  page_from      INTEGER,                         -- 원본 PDF 쪽
  page_to        INTEGER,
  UNIQUE (unit_key, status, effective_from)
);
CREATE INDEX idx_chunk_section ON kb_chunks (section_id);
CREATE INDEX idx_chunk_doc ON kb_chunks (doc_id, status);
CREATE INDEX idx_chunk_article ON kb_chunks (article);
CREATE INDEX idx_chunk_jo ON kb_chunks (doc_id, jo, jo_sub, hang);

-- [추가] 별표·서식. 법령 본문 응답의 '별표' 칸에서 그대로 옮긴다
CREATE TABLE kb_annexes (
  annex_id       INTEGER PRIMARY KEY,
  doc_id         INTEGER NOT NULL REFERENCES kb_documents(doc_id),
  annex_key      TEXT NOT NULL,                   -- 법령|별표|1-2 / 법령|서식|3
  kind           TEXT NOT NULL,                   -- 별표 / 서식
  annex_no       INTEGER NOT NULL,                -- 별표 1의2 → 1 (번호 없는 별표는 0)
  annex_sub      INTEGER NOT NULL DEFAULT 0,      -- 별표 1의2 → 2
  label          TEXT NOT NULL,                   -- 인용 표기: 별표 1의2 / 별지 제3호서식
  title          TEXT,
  text           TEXT,                            -- API 별표내용 원문(줄 그대로). 표는 상자 그림 문자 그대로
  text_rows      TEXT,                            -- 읽기용 글(파생): 고정폭 줄바꿈을 이어 문단으로, 상자 표는 '칸 | 칸' 행으로
  status         TEXT NOT NULL,                   -- CURRENT / PENDING
  effective_from TEXT,
  text_hash      TEXT,
  pdf_link       TEXT,
  file_link      TEXT,
  related        TEXT,                            -- 제목의 '(제N조 관련)' 부분
  source_file    TEXT NOT NULL,
  UNIQUE (annex_key, status, effective_from)
);

-- [추가] 조문 연결. 출발 키 → 도착 키
CREATE TABLE kb_links (
  link_id        INTEGER PRIMARY KEY,
  link_type      TEXT NOT NULL CHECK (link_type IN ('본문참조','위임','별표','준용')),
  src_key        TEXT NOT NULL,                   -- 출발 청크/별표 키
  src_chunk_id   INTEGER REFERENCES kb_chunks(chunk_id),
  src_annex_id   INTEGER REFERENCES kb_annexes(annex_id),
  dst_key        TEXT NOT NULL,                   -- 본문이 가리킨 그대로의 키(가장 세밀한 단위). KB 밖이면 '밖:법령명|…'
  dst_doc_id     INTEGER REFERENCES kb_documents(doc_id),
  dst_chunk_id   INTEGER REFERENCES kb_chunks(chunk_id),
  dst_annex_id   INTEGER REFERENCES kb_annexes(annex_id),
  resolution     TEXT NOT NULL,                   -- 정확 / 상위단위 / 문서 / 미해결
  unresolved_reason TEXT,                         -- KB밖_법령 / 대상없음 / 삭제된_조문 / API대응모호 …
  evidence       TEXT,                            -- 근거 글(본문 일부 또는 API 조항호목)
  origin         TEXT NOT NULL                    -- 본문 / lsDelegated / 별표제목
);
CREATE INDEX idx_link_src ON kb_links (src_chunk_id);
CREATE INDEX idx_link_dst ON kb_links (dst_chunk_id);

-- [추가] 안내서 절 목록(D7). 묶음 청크의 section_path로 만든 목차 나무.
-- 절 읽기(read_section) = 노드 하나와 그 아래 잎의 청크를 chunk_id 순으로 읽기.
CREATE TABLE guide_sections (
  section_id     TEXT PRIMARY KEY,                -- G문서-sha256(목차 경로)[:10]. 같은 묶음이면 늘 같은 값
  doc_id         INTEGER NOT NULL REFERENCES kb_documents(doc_id),
  parent_id      TEXT REFERENCES guide_sections(section_id), -- 문서 뿌리 노드는 NULL
  depth          INTEGER NOT NULL,                -- 목차 경로 길이(뿌리 = 문서 제목 = 1)
  title          TEXT NOT NULL,                   -- 경로 마지막 제목. '(본문)'은 자식 절이 있는 노드 자신의 청크
  path           TEXT NOT NULL,                   -- 목차 경로 JSON
  path_text      TEXT NOT NULL,                   -- 'A > B > C'
  ord            INTEGER NOT NULL,                -- 문서 안 전위 순서
  num            TEXT NOT NULL,                   -- 읽기용 번호(2.3.1, '(본문)'은 .0). 뿌리는 ''
  is_leaf        INTEGER NOT NULL,
  synthetic      INTEGER NOT NULL,                -- 1 = '(본문)' 잎(묶음 경로에 없는 노드)
  page_from      INTEGER, page_to INTEGER,        -- 아래 청크 전체의 쪽 범위
  chunk_from     INTEGER, chunk_to INTEGER,       -- 아래 청크 chunk_id 최소·최대
  chunk_count    INTEGER NOT NULL,                -- 아래 청크 수(자기 포함)
  own_chunk_count INTEGER NOT NULL,               -- 이 노드에 바로 속한 청크 수(잎만 0보다 큼)
  char_count     INTEGER NOT NULL,                -- 아래 청크 글자 수 합
  chunk_runs     INTEGER NOT NULL,                -- 아래 청크가 몇 덩어리로 흩어져 있나(1 = 연속)
  UNIQUE (doc_id, path)
);
CREATE INDEX idx_section_doc ON guide_sections (doc_id, ord);

-- [추가] 빌드 정보
CREATE TABLE build_info (k TEXT PRIMARY KEY, v TEXT NOT NULL);

-- [추가] 쟁점 꾸러미(D6, 3단계). 입력: 수집/꾸러미_v1.json, 꾸러미_v2.json(db/packages.py가 검사하고 넣음).
-- 버전마다 행이 따로 있다(v1·v2 둘 다). 에이전트는 is_current=1 버전을 읽고, 평가에는 쓴 버전을 기록한다.
-- 에이전트는 판정된 쟁점의 꾸러미를 통째로 읽는다: package_items(역할 순서) → package_item_units → 원문.
CREATE TABLE issue_packages (
  version         TEXT NOT NULL,                  -- v1 / v2. 고치면 올리고 평가에 기록
  package_id      TEXT NOT NULL,                  -- PKG01~PKG14(버전이 달라도 같은 쟁점은 같은 ID)
  is_current      INTEGER NOT NULL,               -- 1 = 지금 판(마지막 입력 파일). 한 버전만 1
  issue_name      TEXT NOT NULL,                  -- 현업 issue_catalog 쟁점명 그대로(+ 추가적 이용·제공)
  origin          TEXT NOT NULL,                  -- 현업 issue_catalog / 우리 추가(설계계획 03 D6)
  trigger_json    TEXT NOT NULL,                  -- 현업 issue_catalog trigger 그대로. 추가 쟁점은 v1 제안값 / v2 사실 조건({"fact": …} 원자)
  trigger_status  TEXT NOT NULL CHECK (trigger_status IN ('현업','제안','사실 조건')),
  opened_by       TEXT,                           -- v2 PKG14: 이 꾸러미를 함께 여는 다른 꾸러미 조건 JSON(예 "PKG02.C1a")
  trigger_note    TEXT,
  rules_json      TEXT NOT NULL,                  -- 현업 rule_set_v1.0 규칙 ID 목록
  conditions_json TEXT NOT NULL,                  -- 세부 조건 JSON 목록: id, kind(trigger/하위/제안/사실 조건), when, facts, opens, interp_off,
                                                  -- 팀 P3 줄, 해석 쟁점 후보(interp: v2는 id·status(사실로 풂/해석 쟁점/뺌)·facts)
  excluded_json   TEXT NOT NULL,                  -- 일부러 뺀 조문·절과 이유
  item_count      INTEGER NOT NULL,
  unit_count      INTEGER NOT NULL,               -- 겹침을 뺀 원문 단위 수(청크 + 별표)
  law_chars       INTEGER NOT NULL,               -- 원문 글자 수(현행, 겹침 뺌): 법령 조문
  annex_chars     INTEGER NOT NULL,               --                          별표(읽기용 글)
  guide_chars     INTEGER NOT NULL,               --                          안내서 절
  total_chars     INTEGER NOT NULL,
  basis_chars     INTEGER NOT NULL,               -- 그중 즉답 근거 항목의 글자 수
  input_sha256    TEXT NOT NULL,                  -- 입력 파일 해시
  status          TEXT NOT NULL,                  -- 고정 / 사용자 검토 전. 고정된 버전의 내용이 바뀌면 빌드가 멈춤(version을 올려야 함)
  decided_by      TEXT,                           -- 고정한 사람(고정일 때)
  decided_at      TEXT,
  content_sha256  TEXT NOT NULL,                  -- packages 내용 지문(packages.content_sha256). 입력 frozen 기록과 같음
  PRIMARY KEY (version, package_id),
  UNIQUE (version, issue_name)
);

CREATE TABLE package_items (
  item_id        INTEGER PRIMARY KEY,             -- 버전을 넘어 이어 붙임(v1 1~286, v2 287~)
  version        TEXT NOT NULL,
  package_id     TEXT NOT NULL,
  ord            INTEGER NOT NULL,                -- 입력 파일 순서(법 → 위임 시행령·고시 → 별표 → 안내서)
  target_kind    TEXT NOT NULL,                   -- LAW_UNIT / ANNEX / GUIDE_SECTION. 사규는 나중에 POLICY_UNIT 추가(packages.KINDS)
  target_key     TEXT NOT NULL,                   -- unit_key 또는 조 접두('법령|조|') / annex_key / section_id
  target_doc_id  INTEGER NOT NULL REFERENCES kb_documents(doc_id),
  role           TEXT NOT NULL CHECK (role IN ('핵심','요건','예외','절차','해석')),
  reason         TEXT NOT NULL,                   -- 고른 이유 한 줄
  source         TEXT NOT NULL,                   -- 현업 / 팀P3 / 위임연결 / 본문참조 / 안내서목차 / D6계획 / 우리판단
  conds          TEXT,                            -- 이 항목이 걸리는 세부 조건 ID JSON. NULL = 모든 조건
  answer_basis   INTEGER NOT NULL,                -- 1 = 즉답 근거 범위 안(ANSWER_BASIS + use_scope). 0이면 인용 검증에서 걸러짐
  basis_note     TEXT,                            -- answer_basis=0인 이유
  unit_count     INTEGER NOT NULL,
  char_count     INTEGER NOT NULL,
  FOREIGN KEY (version, package_id) REFERENCES issue_packages(version, package_id),
  UNIQUE (version, package_id, target_kind, target_key)
);
CREATE INDEX idx_pkg_item ON package_items (version, package_id, ord);

-- [파생] 항목을 원문 단위로 펼친 목록(현행 기준). 기준일 판은 unit_key로 in_force 조회
CREATE TABLE package_item_units (
  item_id   INTEGER NOT NULL REFERENCES package_items(item_id),
  ord       INTEGER NOT NULL,
  unit_key  TEXT NOT NULL,                        -- 청크 unit_key / 별표 annex_key
  chunk_id  INTEGER REFERENCES kb_chunks(chunk_id),
  annex_id  INTEGER REFERENCES kb_annexes(annex_id),
  PRIMARY KEY (item_id, ord)
);
CREATE INDEX idx_pkg_unit_chunk ON package_item_units (chunk_id);
