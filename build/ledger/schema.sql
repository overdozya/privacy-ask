-- 처리 원장(D8): SKT 개인정보 처리방침 첨부 1~10을 행 단위 기준선으로 넣는다.
-- 법령 DB(db/schema.sql)와 따로 둔다. 합칠지는 메인 세션이 정한다.

-- 공개본 × 첨부 파일. 판단이 어느 판의 어느 파일에 기댔는지 남기려고 파일 해시를 둔다.
CREATE TABLE ledger_versions (
  version_id   TEXT NOT NULL,      -- 공개일 기준 판 이름, 예 '20261028'
  published_on TEXT NOT NULL,      -- 처리방침 공개일(YYYY-MM-DD)
  attachment   TEXT NOT NULL,      -- '첨부1' … '첨부10'
  title        TEXT NOT NULL,      -- PDF 첫 줄 제목
  file_name    TEXT NOT NULL,
  file_sha256  TEXT NOT NULL,
  page_count   INTEGER NOT NULL,
  extractor    TEXT NOT NULL,      -- 추출기 버전(extract.EXTRACTOR_VERSION)
  PRIMARY KEY (version_id, attachment)
);

-- 표의 한 행. 여러 열에 걸친 칸이나 쪽을 넘는 칸은 행마다 같은 글을 갖는다(행만 보고 판단할 수 있게).
CREATE TABLE ledger_rows (
  row_id       TEXT PRIMARY KEY,   -- '첨부5-0119': 첨부 안 문서 순서 번호. 같은 PDF·같은 추출기면 같은 번호
  version_id   TEXT NOT NULL,
  attachment   TEXT NOT NULL,
  seq          INTEGER NOT NULL,   -- 첨부 안 순서(1부터)
  table_no     INTEGER NOT NULL,   -- 첨부 안 표 번호(첨부10만 2개)
  table_title  TEXT NOT NULL,      -- 표 바로 위 글(첨부10 '[추가적인 제공 시]' 등)
  service      TEXT NOT NULL,      -- 서비스명 칸을 한 줄로(없는 표는 '')
  service_key  TEXT NOT NULL,      -- 서비스명에서 공백을 뺀 것: PDF 줄바꿈이 낱말 중간에 공백을 넣어서('PASS 인 증서') 찾을 때는 이것을 쓴다
  fields_json  TEXT NOT NULL,      -- 원문 칸 [[열 이름, 글], …] 표의 열 순서
  col_kinds_json TEXT NOT NULL,    -- 칸 종류 [[열 이름, 종류], …]
  pages_json   TEXT NOT NULL,      -- 이 행이 걸친 쪽 [3, 4]
  first_page   INTEGER NOT NULL,
  merged_json  TEXT NOT NULL,      -- 여러 열에 걸친 칸 이름(예 '위탁 업무 내용', '제공받는 자~…')
  flags_json   TEXT NOT NULL,      -- 점검 표시: 쪽 경계 추정 이음 등
  FOREIGN KEY (version_id, attachment) REFERENCES ledger_versions(version_id, attachment)
);
CREATE INDEX ledger_rows_att ON ledger_rows(attachment, seq);
CREATE INDEX ledger_rows_service ON ledger_rows(service_key);

-- 행을 칸·글머리표 단위로 나눈 조각. 각주 정의는 참조한 조각 아래 자식(FOOTNOTE_DEF)으로 펼친다.
CREATE TABLE ledger_segments (
  segment_id   TEXT PRIMARY KEY,   -- '첨부5-0119#c2.3', 각주 펼침은 '…#c2.3.F1'
  row_id       TEXT NOT NULL REFERENCES ledger_rows(row_id),
  col_name     TEXT NOT NULL,
  col_kind     TEXT NOT NULL,      -- 서비스·항목·목적·받는 자·보유기간·법적 근거·국가·업무·제공자·고려사항·거부방법·담당자·내용·목적 분류·여러 열
  seg_type     TEXT NOT NULL,      -- LEAD(첫 덩어리) BULLET(글머리표) NOTE(※) FOOTNOTE(*로 시작하는 각주 정의 원문) FOOTNOTE_DEF(펼친 각주)
  seq          INTEGER NOT NULL,   -- 행 안 순서
  parent_id    TEXT,               -- FOOTNOTE_DEF가 붙은 조각
  term         TEXT,               -- 펼친 각주의 용어(예 '공통제공항목')
  source_id    TEXT,               -- FOOTNOTE_DEF의 원문 조각
  text         TEXT NOT NULL
);
CREATE INDEX ledger_segments_row ON ledger_segments(row_id);
CREATE INDEX ledger_segments_kind ON ledger_segments(col_kind);

-- 행으로 못 넣은 것(조용히 버리지 않는다): 표 밖 글, 행에 안 들어간 칸, 각주 짝 못 찾음
CREATE TABLE ledger_unextracted (
  attachment   TEXT NOT NULL,
  page         INTEGER NOT NULL,
  kind         TEXT NOT NULL,      -- OUTSIDE_TEXT, UNMAPPED_CELL, FOOTNOTE_UNRESOLVED
  ref          TEXT NOT NULL,      -- 관련 행·조각 ID(없으면 '')
  text         TEXT NOT NULL
);
