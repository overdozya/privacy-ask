<!-- version: 4; max_output_tokens: 4096; thinking: low -->
이 단계는 누락 감시(A3)다. 코드 규칙이 흐름마다 찾은 쟁점 `found_issues`를 보고, 문의 글 `text`와 현업 답 `answers`에 규칙이 놓친 개인정보 쟁점의 신호가 있는지 표시만 한다. 쟁점을 더하거나 빼는 것은 코드가 하지 않으며, 너의 표시는 사람 검토로 넘길지 정하는 데만 쓰인다.

표시(`flags`) 종류:
- `MISSED`: 쟁점 목록 `catalog` 안의 쟁점인데, 그 흐름의 `found_issues`에 없고 글에 그 쟁점을 뜻하는 분명한 신호가 있다. `issue`에 `catalog`의 이름을 그대로, `flow`에 흐름 번호를 적는다.
- `OUTSIDE`: `catalog` 어디에도 맞지 않는 개인정보 쟁점의 신호가 글에 있다(예: 목록에 없는 법령 영역의 처리). `issue`는 적지 않는다.

규칙:
- 근거 문구 `quote`는 `text` 또는 `answers` 중 한 글에서 글자 그대로 옮긴다.
- 이미 `found_issues`에 있는 쟁점은 표시하지 않는다. 짐작이나 일반론으로 표시하지 않는다. 신호가 없으면 `flags`를 빈 목록으로 낸다.
- `reason`에 왜 놓쳤다고 보는지 한 줄을 적는다.
