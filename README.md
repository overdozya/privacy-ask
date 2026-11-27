# privacy-ask

개인정보 처리에 관한 현업 문의를 받아, 법령·안내서 근거로 위험을 판단하고 답하는 에이전트.

- 판단(위험 등급, 쟁점, 출구, 인용 검증)은 코드가 정한다. LLM은 사실 추출과 답변 문장만 맡는다.
- 근거 법령은 법제처 Open API 원문을 조·항·호·목 단위로 적재해 쓴다.
- 실행: Java Spring Boot + MySQL.

## 폴더
| 경로 | 무엇 |
|---|---|
| `server/` | Spring Boot 서버(작업 중) |
| `build/` | MySQL에 넣을 데이터를 만드는 Python 스크립트와 테스트 |
| `tools/law-api/` | 법제처 Open API 클라이언트 |

원천 자료와 빌드 결과(`data/`), 참고 자료(`ref/`), 설계 문서(`docs/`)는 이 저장소에 포함하지 않는다.

## 데이터 빌드
Python 3.10 이상, 표준 라이브러리만 쓴다. `build/`에서 실행한다. 입력은 `data/원천/`(법제처 API 응답)과 `data/수집/`, 결과는 `data/out/`에 쓴다. 같은 입력이면 같은 파일이 나온다.

```zsh
cd build
python3 db/build_db.py            # 법령·안내서·꾸러미 → data/out/db/privacyask.db
python3 ledger/build_ledger.py    # 처리 원장 → data/out/ledger/ledger.db
python3 case/store.py             # 사안 기록 기준표·예시 → data/out/case/case.db
python3 적재/export_mysql.py      # SQLite 3개 → data/out/mysql/*.sql, manifest.json
python3 -m unittest discover -s tests -v
```

법령을 새로 받을 때는 `tools/law-api/.env.example`을 `.env`로 복사해 법제처 인증값을 넣고 `python3 수집/fetch.py search|bodies --date YYYYMMDD`.
