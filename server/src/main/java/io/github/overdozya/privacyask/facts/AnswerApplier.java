package io.github.overdozya.privacyask.facts;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.github.overdozya.privacyask.common.AppClock;
import io.github.overdozya.privacyask.common.CodeVersion;
import io.github.overdozya.privacyask.knowledge.Catalog;
import io.github.overdozya.privacyask.knowledge.Knowledge;
import io.github.overdozya.privacyask.record.CaseInput.NewFact;
import io.github.overdozya.privacyask.record.CaseInput.NewStep;
import io.github.overdozya.privacyask.record.CaseInput.Value;
import io.github.overdozya.privacyask.record.CaseLock;
import io.github.overdozya.privacyask.record.CaseState;
import io.github.overdozya.privacyask.record.CaseStore;

/**
 * 보기 답 → 사실(06 3.3 A1, 1.5 AnswerApplier). LLM 없이 코드가 적는다: 출처 ANSWER, 상태 CONFIRMED
 * ('잘 모르겠음'류 = UNKNOWN 보기면 현업 미확인 UNKNOWN), 근거 문구 = 고른 보기 글(답 턴 글에 있으면, 없으면 턴 글 전체).
 * 허용 값 밖 답은 저장하지 않는다: S7이 답 턴을 커밋하기 전에 invalidReason으로 거르고 422(06 3.4).
 */
public class AnswerApplier {
    public static final String STEP = "A1", NAME = "선택지 답 기록";

    public record Result(List<Integer> steps, long factRowId, String status) {}

    private final CaseStore store;
    private final CaseLock lock;
    private final Knowledge knowledge;
    private final AppClock clock;
    private final CodeVersion code;

    public AnswerApplier(CaseStore store, CaseLock lock, Knowledge knowledge, AppClock clock, CodeVersion code) {
        this.store = store;
        this.lock = lock;
        this.knowledge = knowledge;
        this.clock = clock;
        this.code = code;
    }

    /** 허용 값 밖이면 이유, 괜찮으면 null. detail = DETAIL 보기(예: '예(나라)')에 붙는 자유 입력. */
    public String invalidReason(String factId, List<String> choices, String detail) {
        Optional<Catalog.Fact> c = knowledge.snapshot().catalog().fact(factId);
        if (c.isEmpty()) {
            return "없는 사실 " + factId;
        }
        Catalog.Fact f = c.get();
        if (choices == null || choices.isEmpty()) {
            return "고른 보기가 없음";
        }
        if (choices.stream().distinct().count() != choices.size()) {
            return "같은 보기를 두 번 고름";
        }
        if (choices.size() > 1 && !f.multi()) {
            return factId + "는 하나만 고르는 사실";
        }
        boolean needDetail = false;
        for (String ch : choices) {
            Optional<Catalog.Option> o = f.option(ch);
            if (o.isEmpty()) {
                return "'" + ch + "'는 " + factId + " 보기에 없음";
            }
            if ("UNKNOWN".equals(o.get().kind()) && choices.size() > 1) {
                return "'" + ch + "'는 다른 보기와 같이 고를 수 없음";
            }
            if ("MATCH".equals(o.get().kind()) || "NO_MATCH".equals(o.get().kind())) {
                return factId + "는 원장 사실(묻지 않음)";
            }
            needDetail |= "DETAIL".equals(o.get().kind());
        }
        if (needDetail && (detail == null || detail.isBlank())) {
            return "'" + choices + "'에는 내용(예: 나라, 법령·조문)을 같이 적어야 함";
        }
        return null;
    }

    /** 답 턴(answerTurn)이 받는 질문의 사실을 적는다. 답 턴은 이미 커밋돼 있어야 한다(06 3.4 tx1). */
    public Result apply(CaseState st, int answerTurn, String detail) {
        String caseId = st.caseRow().caseId();
        CaseState.Turn ans = st.turn(answerTurn).filter(t -> "ANSWER".equals(t.kind()))
                .orElseThrow(() -> new IllegalArgumentException("답 턴 " + answerTurn + " 없음"));
        CaseState.Turn q = st.turn(ans.replyTo()).orElseThrow();
        Catalog.Fact f = knowledge.snapshot().catalog().fact(q.factId()).orElseThrow();
        String bad = invalidReason(q.factId(), ans.choices(), detail);
        if (bad != null) {
            throw new IllegalArgumentException("허용 값 밖 답(INVALID_ANSWER): " + bad);
        }
        List<Value> vals = new ArrayList<>();
        boolean unknown = false;
        for (String ch : ans.choices()) {
            Catalog.Option o = f.option(ch).orElseThrow();
            unknown |= "UNKNOWN".equals(o.kind());
            vals.add(new Value(ch, "DETAIL".equals(o.kind()) ? detail.strip() : null));
        }
        String status = unknown ? "UNKNOWN" : "CONFIRMED";
        String text = ans.text() == null ? "" : ans.text();
        String quote = ans.choices().stream().allMatch(text::contains) ? String.join(" … ", ans.choices()) : text;
        LocalDateTime at = clock.nowUtc();
        return lock.write(caseId, st.inputMark(), STEP, NAME, null, () -> {
            Map<String, Object> in = new LinkedHashMap<>();
            in.put("turn", answerTurn);
            Map<String, Object> fo = new LinkedHashMap<>();
            fo.put("flow", q.flowNo());
            fo.put("fact", q.factId());
            fo.put("values", vals.stream().map(v -> v.detail() == null ? List.of(v.label()) : List.of(v.label(), v.detail())).toList());
            fo.put("status", status);
            fo.put("source", "ANSWER");
            fo.put("turn", answerTurn);
            fo.put("quote", quote);
            int seq = store.addStep(NewStep.code(caseId, STEP, NAME, in, Map.of("facts", List.of(fo)), at, null, code.value()));
            long id = store.addFact(new NewFact(caseId, q.factId(), vals, status, "ANSWER", seq, at, q.flowNo(),
                    answerTurn, quote, List.of(), null, null));
            return new Result(List.of(seq), id, status);
        });
    }
}
