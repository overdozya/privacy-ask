# 법제처 JSON 수집 자료와 코드

2026-10-30, Risk 대응 AI Agent. Python 3.10 이상, 외부 패키지 없이 실행합니다.

**실제 수집:** 법률·시행령·고시·지침 17개, 체계도 5건, 위임 관계 10건. 이 원문에서 조문 98개와 별표·서식 14개를 검색·검토 후보로 골랐습니다. 판례·결정례는 수집하지 않았습니다.

수집 기준은 `collection_policy.json`, 문서별 용도와 확장 보류 8개는 `project_scope.json`에 있습니다. 권한 신청과 실제 자료 채택은 구분합니다.

## 공유 파일

| 파일 | 용도 |
|---|---|
| `원본_JSON/` | 실제 본문. 링크에 반영된 OC만 가림 |
| `법령_통합.json` | 17개 본문·버전·출처·용도를 하나로 묶음 |
| `수집목록.json`, `수집기록/` | 문서명·시각·요청·해시·선택 버전·API 응답 |
| `가공_JSON/조문_검색후보.jsonl` | 조문 98개와 원문 위치·버전·선정 이유 |
| `가공_JSON/법령연결.json` | 위임·인용 연결 715개. 의무 715개라는 의미는 아님 |
| `가공_JSON/법령연결_확인필요.json` | 복수 대상의 대응 확인이 필요한 18개 그룹 |
| `가공_JSON/선정_별표서식.json` | 신고서·제재·시설 기준 등 14개 후보 |
| `가공_JSON/판례_결정례_수집기준.json` | 판례를 쟁점별로 찾아 검토·채택하는 기준 |

`source_file`과 `source_json_pointer`를 따라가면 원문을 확인할 수 있습니다. 원문 전체는 맥락·부칙 확인용으로 보관하고, 검색할 조문은 별도로 선택합니다. 선정 조문은 **최종 의무표나 법 적용이 확정된 규칙이 아닙니다.**

별표·서식 14개 중 13개는 API 본문 텍스트가 있습니다. 기반시설 대응·복구 고려사항 1개는 파일 링크를 보존했습니다. HWP/PDF 파일 자체는 수집하지 않았습니다.

## 코드

- `law_api.py`: 목록 검색 → 이름·시행 버전 확인 → 본문 수집.
- `collect_related.py`: 수집한 핵심 문서의 체계도·위임 관계 조회. 재귀 수집 없음.
- `prepare_dataset.py`: 조문·서식·관계 선별. 복수 대상의 대응이 모호하면 별도 기록.
- `package_data.py`: 해시·인증값 노출 여부 확인 후 ZIP 생성.
- `api_catalog.json`: API 33개의 요청변수·출력 필드·용도·출처·검증 여부.
- `법제처_JSON_API.postman_collection.json`: JSON 요청 31개. 목록 확인 후 식별자를 직접 설정.

공식 가이드에서 JSON 지원을 확인한 API는 31개이며, 이 중 **법령 목록·본문, 행정규칙 목록·본문, 체계도, 위임 관계의 6개 API**를 실제 인증 호출로 검증했습니다. 다른 예제의 실제 수신까지 검증했다는 뜻은 아닙니다. HTML 전용 `lsHistory` 목록·본문은 JSON 요청 모음에서 제외했습니다.

## 재수집

[API 신청](https://open.law.go.kr/LSO/usrJoin.do)에서 대한민국 현행법령과 현행 행정규칙의 **목록 JSON + 본문 JSON**, 실제 호출 서버의 IP·도메인을 등록합니다. 별표·서식 별도 검색은 해당 목록 JSON을 신청합니다.

이 폴더의 `.env`에 `LAW_API_OC=등록한값`을 저장하거나 같은 환경변수를 사용합니다. `.env`는 공유하지 않습니다.

```sh
python3 law_api.py plan --profile initial
python3 law_api.py collect --profile initial --output downloads/new-run
python3 collect_related.py --input downloads/new-run
python3 prepare_dataset.py --input downloads/new-run
python3 package_data.py --input downloads/new-run --output 법제처_JSON_수집자료.zip
```

출력은 새 경로를 사용합니다. `--slug privacy-law`는 한 문서, `--group core`는 핵심 8개로 좁힙니다. `--profile candidates`는 보류 후보까지 25개를 선택하므로 해당 범위가 필요할 때만 사용합니다. 어느 모드에도 판례 일괄 수집은 없습니다.

## 목록과 본문

```sh
python3 law_api.py request --api lsEfYdListGuide --param 'query=개인정보 보호법' --param nw=3 --param display=20 --param page=1
```

법령 본문은 목록의 `법령일련번호`를 `MST`, `시행일자`를 `efYd`로 전달합니다. `ID` 조회는 현행 본문이며 `efYd`는 무시됩니다. 조문은 `JO=34`나 `JO=48-3`처럼 넣으면 6자리로 변환합니다.

행정규칙은 `nw=1`이 현행이고, 본문 요청 `ID`에는 `행정규칙일련번호`를 사용합니다. 법령 검색의 `nw=3`, 본문의 `MST`와 구별합니다.

`request`의 목록 조회는 지정 페이지 한 장만 받습니다. `collect`만 정해둔 문서 이름을 검색하며 페이지를 순회합니다. Postman 예제 역시 식별자를 자동 전달하는 일괄 실행 기능이 아닙니다.

## 판례 선별

1. 서비스가 답할 구체적인 쟁점과 필요한 이유를 적습니다.
2. 쟁점·관계 법령으로 목록 10건 이내부터 확인합니다.
3. 사건명·법원·선고일·사건번호를 보고 검토 후보와 이유를 기록합니다.
4. 후보 일련번호로 본문을 읽습니다. 처음에는 쟁점당 최대 3건을 검토합니다.
5. 사실관계·적용 법령 시점·판단 내용을 확인해 채택·제외합니다.

10건·3건은 이번 작업의 시작 한도이며 공식 API 제한이 아닙니다. 법·하위 규정으로 해결되는 문제에는 판례를 추가하지 않습니다.

## 검증

```sh
python3 -m unittest -v test_law_api.py
```

HTTP 200이어도 인증 실패 JSON이나 미신청 권한 HTML이 올 수 있어 구별합니다. `samples/`의 공개 인증값 실패 기록은 실제 법령 샘플이 아닙니다.

이름·버전, 본문 존재, 해시, 원문 위치와 선정 조문 누락을 확인했습니다. 법령 관계 중 복수 대상의 연결이 불명확한 부분은 임의로 짝짓지 않았습니다. 다른 버전이나 아직 수집하지 않은 법령으로 향하는 링크도 그대로 표시합니다.

출처는 각 API의 `guide_url`과 [공식 활용가이드](https://open.law.go.kr/LSO/openApi/guideList.do)입니다.
