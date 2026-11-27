package io.github.overdozya.privacyask.verify;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import io.github.overdozya.privacyask.answer.Answer;
import io.github.overdozya.privacyask.common.CodeVersion;
import io.github.overdozya.privacyask.issues.CaseJudge;
import io.github.overdozya.privacyask.issues.IssueDeriver;
import io.github.overdozya.privacyask.issues.PreliminaryExit;
import io.github.overdozya.privacyask.issues.SlotResult;
import io.github.overdozya.privacyask.knowledge.KnowledgeSnapshot;
import io.github.overdozya.privacyask.record.CaseInput;
import io.github.overdozya.privacyask.record.CaseInput.CitationEntry;
import io.github.overdozya.privacyask.record.CaseInput.IssueEntry;
import io.github.overdozya.privacyask.record.CaseInput.NewJudgment;
import io.github.overdozya.privacyask.record.CaseInput.NewStep;
import io.github.overdozya.privacyask.record.CaseInput.SlotEntry;
import io.github.overdozya.privacyask.record.CaseLock;
import io.github.overdozya.privacyask.record.CaseState;
import io.github.overdozya.privacyask.record.CaseStore;

/**
 * A8 판정 기록(03 4.2 A8, 06 2.1 V6). 한 트랜잭션에서 A8 단계 → case_judgments*(CaseStore.addJudgment, 거부 C11~C17 그대로) → 사안
 * 상태 JUDGED(즉답)·TRANSFERRED(이관) + closed_at.
 *
 * <p>OPEN 슬롯(메인 결정): 판정 행에는 UNKNOWN으로 적고 막은 사실을 미확인 칸에 더한다(SlotEntry.openFacts = 'OPEN에서 옴' 표시,
 * C16이 그 사실만은 NOT_FOUND·행 없음도 받음). 값이 있었으면 C14(UNKNOWN은 값 없음) 때문에 값은 flags.open_slots에만 남긴다.
 * 응답을 판정 테이블에서 다시 지을 수 있게 flags에 구조로 된 사유·흐름별 켜진 규칙·슬롯 표시·하위 꾸러미 해석 쟁점·unasked를 둔다.
 */
public final class JudgmentRecorder {
    public static final String STEP = "A8", NAME = "판정 기록";

    /** 판정 하나의 재료. steps = 이 판정이 기댄 단계 번호(이름 → seq, A8 단계 input에 남김). */
    public record Input(CaseState state, CaseJudge.Judgment judgment, ExitAssembler.FinalExit exit, Answer answer,
                        CitationVerifier.Report report, Map<String, Object> bundle, List<String> unasked,
                        LocalDate openDate, Map<String, Integer> steps) {}

    public record Written(int stepSeq, long judgmentId, String state) {}

    private final CaseStore store;
    private final CaseLock lock;
    private final CodeVersion code;

    public JudgmentRecorder(CaseStore store, CaseLock lock, CodeVersion code) {
        this.store = store;
        this.lock = lock;
        this.code = code;
    }

    public Written write(KnowledgeSnapshot k, Input in, LocalDateTime at) {
        CaseState st = in.state();
        String caseId = st.caseRow().caseId();
        String state = in.exit().transfer() ? "TRANSFERRED" : "JUDGED";
        Map<String, Object> stepIn = new LinkedHashMap<>();
        stepIn.put("from_steps", in.steps());
        Map<String, Object> stepOut = new LinkedHashMap<>();
        stepOut.put("exit_type", in.exit().exitType());
        stepOut.put("state", state);
        return lock.write(caseId, st.inputMark(), STEP, NAME, null, () -> {
            int seq = store.addStep(NewStep.code(caseId, STEP, NAME, stepIn, stepOut, at, null, code.value()));
            NewJudgment j = judgment(k, in, seq, at);
            long id = store.addJudgment(j);
            Map<String, Object> set = new LinkedHashMap<>();
            set.put("state", state);
            set.put("closed_at", at);
            store.updateCase(caseId, at, set);
            return new Written(seq, id, state);
        });
    }

    NewJudgment judgment(KnowledgeSnapshot k, Input in, int seq, LocalDateTime at) {
        CaseState st = in.state();
        CaseState.CaseRow c = st.caseRow();
        // 모델·프롬프트: 이 사안 LLM 단계 전부(C17은 상태와 상관없이 모든 LLM 단계와 같아야 함)
        Set<CaseInput.PromptRef> prompts = new LinkedHashSet<>();
        String model = null;
        BigDecimal temp = null;
        for (CaseState.StepSummary s : st.steps()) {
            if ("LLM".equals(s.actor())) {
                prompts.add(new CaseInput.PromptRef(s.promptId(), s.promptVersion()));
                if (model == null) {
                    model = s.modelName();
                    temp = s.temperature();
                }
            }
        }
        List<SlotEntry> slots = new ArrayList<>();
        List<IssueEntry> issues = new ArrayList<>();
        List<Map<String, Object>> flowFlags = new ArrayList<>(), openSlots = new ArrayList<>();
        List<String> factConflict = new ArrayList<>(), checkItems = new ArrayList<>();
        for (IssueDeriver.FlowIssues f : in.judgment().flows()) {
            Map<String, Object> ff = new LinkedHashMap<>();
            ff.put("flow", f.flowNo());
            ff.put("grade", f.grade());
            ff.put("fired_rules", f.firedRules());
            Map<String, Object> slotFlags = new LinkedHashMap<>(), opens = new LinkedHashMap<>();
            for (Map.Entry<String, SlotResult> e : f.slots().entrySet()) {
                SlotResult s = e.getValue();
                String slot = e.getKey();
                List<String> values = s.values();
                List<String> unknown = new ArrayList<>(s.unknownFacts());
                String status = s.status();
                List<String> openMark = List.of();
                if (SlotResult.OPEN.equals(status)) {
                    // 메인 결정: OPEN → UNKNOWN, 막은 사실을 미확인 칸에(C16 'OPEN에서 옴' 표시). 값이 있었으면 C14 때문에 flags에만
                    openMark = s.openFacts().stream().filter(x -> !s.unknownFacts().contains(x)).toList();
                    openMark.forEach(unknown::add);
                    Map<String, Object> o = new LinkedHashMap<>();
                    o.put("flow", f.flowNo());
                    o.put("slot", slot);
                    o.put("blocking", s.openFacts());
                    if (!values.isEmpty()) {
                        o.put("values_not_stored", values);
                    }
                    status = SlotResult.UNKNOWN;
                    values = List.of();
                    o.put("stored_as", status);
                    openSlots.add(o);
                    opens.put(slot, s.openFacts());
                }
                if (!s.flags().isEmpty()) {
                    slotFlags.put(slot, s.flags());
                    for (String fl : s.flags()) {
                        String line = "흐름 " + f.flowNo() + " " + slot;
                        if (PreliminaryExit.FACT_CONFLICT.equals(fl)) {
                            factConflict.add(line);
                        } else if (PreliminaryExit.CHECK_ITEM.equals(fl)) {
                            checkItems.add(line);
                        }
                    }
                }
                List<Long> basis = new ArrayList<>();
                for (String fid : s.basisFacts()) {
                    st.current(f.flowNo(), fid).map(CaseState.Fact::factRowId).ifPresent(basis::add);
                }
                slots.add(new SlotEntry(f.flowNo(), slot, status, values, basis, List.copyOf(unknown), openMark));
            }
            ff.put("slot_flags", slotFlags);
            ff.put("open", opens);
            List<Map<String, Object>> subs = new ArrayList<>();
            for (IssueDeriver.Issue i : f.issues()) {
                Answer.IssueAnswer a = ReviewBundleBuilder.answerOf(in.answer(), f.flowNo(), i.issueName());
                issues.add(new IssueEntry(f.flowNo(), i.issueName(), i.packageId(), i.grade(), i.ruleIds(),
                        i.conditionIds(), i.interpOn(), a != null && a.conclusive(), a == null ? null : a.opinion(),
                        a == null || a.actions() == null || a.actions().isEmpty() ? null
                                : String.join("\n", a.actions().stream().map(x -> "- " + x).toList()),
                        !in.exit().transfer()));
                for (IssueDeriver.SubPackage sp : i.opens()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("issue", i.issueName());
                    m.put("package_id", sp.packageId());
                    m.put("parent_condition", sp.parentCondition());
                    m.put("conditions", sp.conditionIds());
                    m.put("interp", sp.interpOn());
                    subs.add(m);
                }
            }
            ff.put("sub_packages", subs);
            flowFlags.add(ff);
        }
        List<CitationEntry> cites = new ArrayList<>();
        if (in.report() != null) {
            for (CitationVerifier.Checked x : in.report().cites()) {
                Integer idx = null;
                if (x.issue() != null) {
                    for (int n = 0; n < issues.size(); n++) {
                        if (Objects.equals(issues.get(n).flow(), x.flow()) && issues.get(n).issue().equals(x.issue())) {
                            idx = n;
                        }
                    }
                }
                boolean row = "LEDGER_ROW".equals(x.kind());
                cites.add(new CitationEntry(idx, x.kind(), x.key(), x.textHash(), row ? x.ledgerVersion() : null,
                        row ? "LEDGER" : "PACKAGE"));
            }
        }
        Map<String, Object> flags = new LinkedHashMap<>();
        flags.put("fact_conflict", factConflict);
        flags.put("check_items", checkItems);
        flags.put("omission_watch", in.exit().reasons().stream().anyMatch(r -> PreliminaryExit.WATCH_FLAG.equals(r.code())));
        flags.put("open_slots", openSlots);
        flags.put("reasons", in.exit().reasons());
        flags.put("flows", flowFlags);
        flags.put("unasked", in.unasked());
        flags.put("open_date", String.valueOf(in.openDate()));
        flags.put("verify", in.report() == null ? null : in.report().summary());
        LocalDate ref = c.referenceDate() != null ? c.referenceDate() : in.openDate();
        Integer judgedTurn = st.lastUserTurn().map(CaseState.Turn::turnNo).orElse(null);
        return new NewJudgment(c.caseId(), seq, judgedTurn, ref, c.ruleSetVersion(), c.packageVersion(),
                c.ledgerVersion(), k.catalog().factMapSha256(), model, temp, List.copyOf(prompts),
                in.exit().caseGrade(), in.exit().exitType(), in.exit().lines(), in.exit().conclusive(), flags,
                in.exit().transfer() ? in.bundle() : null, NewJudgment.PREMISE_NOTE, at, slots, issues, cites);
    }

    static Optional<Integer> lastUserTurn(CaseState st) {
        return st.lastUserTurn().map(CaseState.Turn::turnNo);
    }
}
