package io.github.overdozya.privacyask.record;

import java.util.Map;

/**
 * 저장 함수 거부(build/case/store.py RULES를 그대로 옮김, 06 2.6). 거부하면 아무것도 남기지 않는다.
 * code "DB"는 Java 검사를 지나 DB 칸 제약이 막은 것(두 번째 벽). 둘 다 API에서는 500 RECORD_REJECTED(코드 결함).
 */
public class Rejected extends RuntimeException {
    public static final Map<String, String> RULES = Map.ofEntries(
            Map.entry("C01", "출처 없음: 출처 종류가 없거나 그 사안의 단계 기록이 없음"),
            Map.entry("C02", "근거 문구 없음: 값이 있는 사실에 근거 문구가 없음"),
            Map.entry("C03", "근거 문구가 출처에 없음: 문구 조각이 문의 원문·답변 턴·원장 행 글 안에 없음"),
            Map.entry("C04", "없는 답변 턴: ANSWER 출처가 그 사안의 현업 답 턴을 가리키지 않음"),
            Map.entry("C05", "원장 출처 조건 위반: 원장 조회가 없는 사실, A3가 아닌 단계, 일치 값인데 행 없음, 원장 밖 출처에 행"),
            Map.entry("C06", "없는 원장 행·판: 처리 원장에 없는 행 ID·처리방침 판, 판이 다른 행, 그 사실이 볼 수 없는 첨부의 행"),
            Map.entry("C07", "없는 사실 ID: 04 대응표에 없는 사실(사실 기록·질문 턴)"),
            Map.entry("C08", "허용 값 밖 사실: 보기에 없는 값, 단일 사실에 값 여럿, 자유 입력 누락·잘못 붙음, 답 턴의 보기 밖 선택"),
            Map.entry("C09", "확인 상태 위반: 모름인데 값 있음, '잘 모르겠음'인데 현업 미확인 아님(또는 그 반대), 원장 출처인데 명시 일치는 MATCHED·그 밖은 INFERRED가 아님, 원장 밖 출처의 MATCHED"),
            Map.entry("C10", "흐름 위반: 공통 사실에 흐름, 흐름 사실에 흐름 없음, 없는 흐름"),
            Map.entry("C11", "꾸러미 버전 없음: 사안·판정에 꾸러미 판이 없거나 지식에 없는 판"),
            Map.entry("C12", "기준 판 불일치: 규칙 세트·대응표 해시가 기준표와 다름, 처리방침 판이 없거나 원장 사실과 다름, 판정의 판이 사안 시작 때 고정한 값과 다름"),
            Map.entry("C13", "없는 인용 키: 조문·별표·절·원장 행 키가 없거나 text_hash가 그 키의 어느 판과도 맞지 않음"),
            Map.entry("C14", "슬롯 위반: 허용 값 밖, 단일 슬롯 값 여럿, 상태와 값이 안 맞음, 흐름마다 8개가 아님"),
            Map.entry("C15", "쟁점 위반: 그 꾸러미 판에 없는 쟁점·조건·해석 쟁점, 없는 규칙 ID, 사안 등급이 쟁점 최고 등급이 아님"),
            Map.entry("C16", "판정 연결 위반: A8 단계가 아님, 다른 사안·흐름의 사실을 근거로 씀, 현업 미확인 아닌 사실을 미확인으로 적음"),
            Map.entry("C17", "LLM 기록 없음: 프롬프트 ID·버전·모델·온도가 없는 호출, 버전이 '<버전>@<해시 8자>' 형식이 아님, 실행 중 프롬프트 목록에 없음, 판정의 프롬프트·모델이 단계 기록과 다름"));

    private final String code;
    private final String detail;

    public Rejected(String code, String detail) {
        super(code + " " + RULES.getOrDefault(code, "DB 제약") + ": " + detail);
        if (!RULES.containsKey(code) && !"DB".equals(code)) {
            throw new IllegalArgumentException("없는 거부 코드: " + code);
        }
        this.code = code;
        this.detail = detail;
    }

    public Rejected(String code, String detail, Throwable cause) {
        this(code, detail);
        initCause(cause);
    }

    public String code() {
        return code;
    }

    public String detail() {
        return detail;
    }
}
