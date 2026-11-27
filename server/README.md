# server

개인정보 판단 에이전트 서버. Java 21 · Spring Boot 4.2 · MySQL 8.4 · Flyway. LLM은 공급자 경계(`llm/LlmProvider`) 뒤에서 직접 부른다(Gemini REST, 테스트는 Fake).

## 띄우기 (한 화면)

준비: JDK 21, Docker. 명령은 모두 `server/`에서 친다.

```bash
cp .env.example .env          # 값을 채운다(LLM_API_KEY, DB_PASSWORD). .env는 git에 넣지 않는다
scripts/mysql-up.sh           # Docker mysql:8.4, 볼륨 privacyask-mysql-data, 127.0.0.1:<DB_URL 포트>
./gradlew bootRun             # 1) 첫 기동: Flyway V1~V7(빈 DB). "지식 테이블이 비어 있다" 경고가 정상. Ctrl+C
scripts/load-data.sh          # 2) 적재 SQL 4개(레포 밖 ../data/out/mysql). 파일마다 끝의 확인 표 ok = OK
./gradlew bootRun             # 3) 다시 기동: "행 수 확인 OK: … = manifest"
./gradlew bootRun --args='--app.llm.ping-on-startup=true'   # LLM 한 번(S0_ping) → llm_call_logs 한 행
```

- 빈 DB로 처음부터: `scripts/mysql-up.sh --reset`(컨테이너·볼륨을 지우고 다시 만든다). 개발 중 DDL을 크게 바꿀 때도 이렇게 V1부터 다시 적용한다.
- 적재 파일 위치는 `PRIVACYASK_DATA_DIR`(기본 `../data`). 행 수가 manifest와 일부만 다르면 기동이 멈춘다(적재를 잊거나 일부만 넣은 것).
- 서버는 `127.0.0.1:8080`에만 붙는다. 인증 없음(요청자는 `X-Requester-Id`).

## 설정

`.env`(KEY=VALUE, 따옴표 없이) → `application.yml`. 같은 이름의 환경변수가 이긴다.

| 이름 | 뜻 |
|---|---|
| `LLM_PROVIDER` | `gemini` / `fake` |
| `LLM_MODEL`, `LLM_TEMPERATURE` | 모델 ID, 온도(호출마다 넣고 기록) |
| `LLM_API_KEY` | 머리 `x-goog-api-key`로만 보낸다. 로그·기록에 남기지 않는다 |
| `DB_URL`, `DB_USER`, `DB_PASSWORD` | `jdbc:mysql://127.0.0.1:<포트>/<DB>`. JDBC 속성(UTC, 묶음 INSERT)은 yml에 있다 |

그 밖의 값(`app.llm.call-timeout` 20s, `app.llm.gemini.send-schema`, `send-temperature` 등)은 `application.yml` 기본값.

## 구조

| 곳 | 무엇 |
|---|---|
| `db/migration/V1~V7` | V1 법령·안내서, V2 꾸러미, V3 처리 원장, V4 기준표·규칙, V5 사안 기록, V6 판정, V7 호출·턴 기록. 시각은 UTC, 키 칸은 `utf8mb4_bin` |
| `prompts/` | `_common.md` + `ID.md`(첫 줄 `<!-- version: N; max_output_tokens: …; thinking: default -->`) + `ID.schema.json`. 기록의 판 = `N@보낸 글 sha256 앞 8자` |
| `llm/LlmGateway` | 스키마 검증 → 업무 검사 → 재시도(검증 실패 1회, 전송 실패 1회, 합쳐 최대 3번, 턴 마감 안에서) → `OK`·`RETRY_OK`·`LLM_FAILED`. 시도마다 `llm_call_logs` 한 행 |
| `common/` | UTC 시계·서울 날짜, 코드 판(빌드 때 git 해시), 기동 행 수 확인 |

## 테스트

```bash
./gradlew test
```

Docker가 있으면 Testcontainers(mysql:8.4)로 V1~V7과 칸 제약을 확인하고, `../data/out/mysql`이 있으면 적재 파일을 두 번 넣어 행 수를 manifest와 견준다(없으면 그 테스트만 건너뜀). 테스트는 `server/.env`를 읽지 않는다.
