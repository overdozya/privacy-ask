package io.github.overdozya.privacyask.verify;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.overdozya.privacyask.answer.Answer;
import io.github.overdozya.privacyask.issues.CaseJudge;
import io.github.overdozya.privacyask.issues.IssueDeriver;
import io.github.overdozya.privacyask.issues.SlotResult;
import io.github.overdozya.privacyask.knowledge.Catalog;
import io.github.overdozya.privacyask.knowledge.KnowledgeSnapshot;
import io.github.overdozya.privacyask.record.CaseInput;
import io.github.overdozya.privacyask.record.CaseState;

/**
 * 검토 요청 묶음(03 4.2 A7 5): 이관이면 코드가 조립한다. 앞 단계가 이미 구조로 낸 것만 옮기고 새로 판단하지 않는다.
 * 담는 것: 사실(근거 문구·확인 상태), 흐름별 쟁점·등급·슬롯, 기준선 대조(원장 사실·행 ID), 꾸러미 조문 키(넘긴 키·인용 키), 이관 사유,
 * 남은 질문(질문 상한으로 안 물은 후보 + OPEN 슬롯을 막은 사실), A5 의견·조치·확인 필요(checks)·gap, 인용 검증 요약.
 * 검토자용 답변 초안은 만들지 않는다(03 7절). 요지(summary)는 ReviewSummaryStep이 이 묶음으로 쓴 뒤 붙인다.
 */
public final class ReviewBundleBuilder {

    /** keys = "흐름|쟁점" → 이 쟁점에 넘긴 꾸러미 원문 키(A4). unasked = "흐름:사실" 꼴(A2 상한으로 남은 후보). */
    public record Input(CaseState state, CaseJudge.Judgment judgment, ExitAssembler.FinalExit exit, Answer answer,
                        CitationVerifier.Report report, Map<String, List<String>> keys, List<String> unasked,
                        String openDate) {}

    private final KnowledgeSnapshot k;

    public ReviewBundleBuilder(KnowledgeSnapshot k) {
        this.k = k;
    }

    public Map<String, Object> build(Input in) {
        CaseState st = in.state();
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("kind", in.answer() == null ? null : in.answer().kind());
        b.put("content", st.caseRow().content());
        b.put("service_name", st.caseRow().serviceName());
        b.put("reference_date", st.caseRow().referenceDate() == null ? null : String.valueOf(st.caseRow().referenceDate()));
        b.put("open_date", in.openDate());
        b.put("case_grade", in.exit().caseGrade());
        b.put("exit_reasons", in.exit().reasons());
        b.put("facts", facts(st));
        b.put("flows", flows(st, in));
        b.put("remaining_questions", remaining(st, in));
        if (in.answer() != null && in.answer().gaps() != null) {
            b.put("gaps", in.answer().gaps());
        }
        if (in.answer() != null && Answer.GENERAL.equals(in.answer().kind())) {
            Map<String, Object> g = new LinkedHashMap<>();
            g.put("picks", in.answer().picks());
            g.put("no_basis", in.answer().noBasis());
            g.put("reason", in.answer().reason());
            g.put("answer_text", in.answer().answerText());
            b.put("general", g);
        }
        b.put("verify", in.report() == null ? null : in.report().summary());
        return b;
    }

    /** 지금 사실(원장 사실 L*는 기준선 칸으로). */
    List<Map<String, Object>> facts(CaseState st) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (CaseState.Fact f : st.currentFacts()) {
            if (f.factId().matches("L\\d+")) {
                continue;
            }
            out.add(fact(f));
        }
        return out;
    }

    Map<String, Object> fact(CaseState.Fact f) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("flow", f.flowNo());
        m.put("fact", f.factId());
        m.put("question", k.catalog().fact(f.factId()).map(Catalog.Fact::question).orElse(null));
        List<Map<String, Object>> vs = new ArrayList<>();
        for (CaseInput.Value v : f.values()) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("label", v.label());
            if (v.detail() != null) {
                x.put("detail", v.detail());
            }
            vs.add(x);
        }
        m.put("values", vs);
        m.put("status", f.status());
        m.put("source", f.sourceKind());
        m.put("quote", f.basisQuote());
        return m;
    }

    List<Map<String, Object>> flows(CaseState st, Input in) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (IssueDeriver.FlowIssues f : in.judgment().flows()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("flow", f.flowNo());
            st.flow(f.flowNo()).ifPresent(x -> {
                m.put("label", x.label());
                m.put("origin", x.origin());
                m.put("destination", x.destination());
                m.put("service_name", x.serviceName());
            });
            m.put("grade", f.grade());
            Map<String, Object> slots = new LinkedHashMap<>();
            for (Map.Entry<String, SlotResult> e : f.slots().entrySet()) {
                SlotResult s = e.getValue();
                Map<String, Object> x = new LinkedHashMap<>();
                boolean open = SlotResult.OPEN.equals(s.status());
                x.put("status", open ? SlotResult.UNKNOWN : s.status());
                x.put("values", s.values());
                x.put("unknown_facts", s.unknownFacts());
                if (open) {
                    x.put("open_facts", s.openFacts());
                }
                if (!s.flags().isEmpty()) {
                    x.put("flags", s.flags());
                }
                slots.put(e.getKey(), x);
            }
            m.put("slots", slots);
            List<Map<String, Object>> ledger = new ArrayList<>();
            for (CaseState.Fact x : st.currentFacts()) {
                if (x.flowNo() != null && x.flowNo() == f.flowNo() && x.factId().matches("L\\d+")) {
                    Map<String, Object> l = new LinkedHashMap<>();
                    l.put("fact", x.factId());
                    l.put("status", x.status());
                    l.put("values", x.labels());
                    l.put("rows", x.ledgerRows());
                    l.put("quote", x.basisQuote());
                    ledger.add(l);
                }
            }
            m.put("baseline", ledger);
            List<Map<String, Object>> issues = new ArrayList<>();
            for (IssueDeriver.Issue i : f.issues()) {
                Map<String, Object> x = new LinkedHashMap<>();
                x.put("issue", i.issueName());
                x.put("package_id", i.packageId());
                x.put("grade", i.grade());
                x.put("rules", i.ruleIds());
                x.put("conditions", i.conditionIds());
                x.put("interp", i.allInterpOn());
                x.put("package_keys", in.keys().getOrDefault(f.flowNo() + "|" + i.issueName(), List.of()));
                Answer.IssueAnswer a = answerOf(in.answer(), f.flowNo(), i.issueName());
                if (a != null) {
                    x.put("opinion", a.opinion());
                    x.put("actions", a.actions());
                    x.put("cites", a.cites());
                    x.put("ledger_rows", a.ledgerRows());
                    x.put("conclusive", a.conclusive());
                    x.put("not_conclusive", a.notConclusive());
                    x.put("checks", a.checks());
                }
                issues.add(x);
            }
            m.put("issues", issues);
            out.add(m);
        }
        return out;
    }

    static Answer.IssueAnswer answerOf(Answer a, int flow, String issue) {
        if (a == null || a.issues() == null) {
            return null;
        }
        return a.issues().stream().filter(x -> x.flow() == flow && x.issue().equals(issue)).findFirst().orElse(null);
    }

    /** 남은 질문: A2 상한으로 안 물은 후보 + OPEN 슬롯을 막은 사실(겹치면 한 번). 질문 글은 기준표 그대로. */
    List<Map<String, Object>> remaining(CaseState st, Input in) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (String u : in.unasked()) {
            String[] p = u.replace("(다시)", "").split(":", 2);
            Integer flow = p.length == 2 && p[0].matches("\\d+") ? Integer.valueOf(p[0]) : null;
            String fact = p.length == 2 ? p[1] : p[0];
            put(out, flow, fact, "UNASKED");
        }
        for (ExitAssembler.OpenSlot o : in.exit().openSlots()) {
            for (String f : o.blocking()) {
                put(out, o.flow(), f, "OPEN_SLOT " + o.slot());
            }
        }
        return List.copyOf(out.values());
    }

    private void put(Map<String, Map<String, Object>> out, Integer flow, String fact, String why) {
        String key = flow + ":" + fact;
        Map<String, Object> m = out.get(key);
        if (m == null) {
            m = new LinkedHashMap<>();
            m.put("flow", flow);
            m.put("fact", fact);
            m.put("question", k.catalog().fact(fact).map(Catalog.Fact::question).orElse(null));
            m.put("why", new ArrayList<String>());
            out.put(key, m);
        }
        @SuppressWarnings("unchecked")
        List<String> w = (List<String>) m.get("why");
        if (!w.contains(why)) {
            w.add(why);
        }
    }
}
