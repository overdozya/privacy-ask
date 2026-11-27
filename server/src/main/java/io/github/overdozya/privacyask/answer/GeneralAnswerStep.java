package io.github.overdozya.privacyask.answer;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.jdbc.core.simple.JdbcClient;

import io.github.overdozya.privacyask.common.AppClock;
import io.github.overdozya.privacyask.common.CodeVersion;
import io.github.overdozya.privacyask.knowledge.Documents;
import io.github.overdozya.privacyask.knowledge.GuideSections;
import io.github.overdozya.privacyask.knowledge.Knowledge;
import io.github.overdozya.privacyask.knowledge.KnowledgeSnapshot;
import io.github.overdozya.privacyask.knowledge.LawUnits;
import io.github.overdozya.privacyask.knowledge.Packages;
import io.github.overdozya.privacyask.llm.CallContext;
import io.github.overdozya.privacyask.llm.LlmGateway;
import io.github.overdozya.privacyask.llm.LlmResult;
import io.github.overdozya.privacyask.packages.ArticleList;
import io.github.overdozya.privacyask.packages.PackageOpener;
import io.github.overdozya.privacyask.record.CaseInput.NewStep;
import io.github.overdozya.privacyask.record.CaseLock;
import io.github.overdozya.privacyask.record.CaseState;
import io.github.overdozya.privacyask.record.CaseStore;
import tools.jackson.databind.json.JsonMapper;

/**
 * A5 답변, 일반 질의 G2(04 결정 12·13, 4.2 표). 모델은 안B 257조 목록('키 + 조 제목')에서 키만 고른다(상한 5, 조마다 이유).
 * 코드가 고른 조의 기준일(오늘) 판 원문을 붙이고, 그 조가 어떤 쟁점 꾸러미의 '핵심'이면 그 꾸러미의 조건 없는 안내서 절도 붙인다.
 * 목록 밖 조가 필요하다고 하거나(no_basis) 고른 조가 없으면 "근거 없음 → 검토 안내"(결정 13): 이관 사유 NO_BASIS.
 * 업무 검사: 고른 키가 목록 안, 같은 키 두 번 없음, no_basis가 거짓인데 고른 조 없음 아님.
 */
public class GeneralAnswerStep {
    public static final String STEP = "A5", PROMPT = "A5_general", NAME = "답변(일반 질의)";
    /** 두 번째 호출(S8b): 고른 조 원문만 보고 2~4문장. 고른 조가 없으면 LLM 없이 코드 단계로 건너뜀을 남긴다. */
    public static final String PROMPT_TEXT = "A5_general_answer", NAME_TEXT = "답변 문장(일반 질의)";

    /** 답 문장 LLM 출력(prompts/A5_general_answer.schema.json). */
    public record GeneralText(String answer, List<String> cites) {
        public GeneralText {
            cites = cites == null ? List.of() : List.copyOf(cites);
        }
    }

    public record TextResult(LlmResult.Status status, GeneralText text, List<Integer> steps, List<Long> callIds,
                             String error) {
        public boolean failed() {
            return status == LlmResult.Status.LLM_FAILED;
        }
    }

    /** 고른 조 키 + 붙인 원문 단위 키. */
    static java.util.Set<String> citable(Answer picked) {
        java.util.Set<String> out = new LinkedHashSet<>();
        picked.picks().forEach(p -> out.add(p.key()));
        picked.sources().forEach(s -> out.add(s.key()));
        return out;
    }

    static String checkText(Answer picked, GeneralText out) {
        List<String> bad = new ArrayList<>();
        if (out.answer() == null || out.answer().isBlank()) {
            bad.add("답 문장 없음");
        }
        if (out.cites().isEmpty()) {
            bad.add("인용 키 없음");
        }
        java.util.Set<String> ok = citable(picked);
        out.cites().stream().filter(c -> !ok.contains(c)).forEach(c -> bad.add("키 '" + c + "'는 고른 조 밖"));
        return bad.isEmpty() ? null : String.join("; ", bad.subList(0, Math.min(4, bad.size())));
    }

    /** 고른 조 원문(+ 안내서 절 제목)만 넘겨 답 문장을 쓴다. 고른 조가 없으면 코드 단계(건너뜀)만. */
    public TextResult runText(CaseState st, Answer picked, Instant deadline) {
        String caseId = st.caseRow().caseId();
        LocalDateTime at = clock.nowUtc();
        if (picked.picks() == null || picked.picks().isEmpty()) {
            int seq = lock.write(caseId, st.inputMark(), STEP, NAME_TEXT, null, () -> store.addStep(NewStep.code(caseId,
                    STEP, NAME_TEXT, Map.of("picks", 0), Map.of("skipped", "고른 조 없음(근거 없음 → 검토 안내)"), at,
                    null, code.value())));
            return new TextResult(LlmResult.Status.OK, null, List.of(seq), List.of(), null);
        }
        Map<String, Object> in = new LinkedHashMap<>();
        in.put("text", st.caseRow().content());
        List<Map<String, Object>> picks = new ArrayList<>();
        for (Answer.Pick p : picked.picks()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", p.key());
            m.put("label", p.label());
            m.put("title", p.title());
            m.put("law", p.lawName());
            m.put("why", p.why());
            picks.add(m);
        }
        in.put("picks", picks);
        List<Map<String, Object>> arts = new ArrayList<>();
        for (Answer.Source s : picked.sources()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", s.key());
            m.put("doc", s.doc());
            m.put("label", s.label());
            m.put("title", s.title());
            m.put("text", s.excerpt());
            arts.add(m);
        }
        in.put("articles", arts);
        List<Map<String, Object>> gs = new ArrayList<>();
        for (Answer.Guide g : picked.guides()) {
            gs.add(Map.of("section_id", g.sectionId(), "doc", String.valueOf(g.doc()), "title", String.valueOf(g.path())));
        }
        in.put("guides", gs);
        LlmResult<GeneralText> r = llm.call(PROMPT_TEXT, in, GeneralText.class, out -> checkText(picked, out),
                new CallContext(caseId, null, deadline, false));
        return lock.write(caseId, st.inputMark(), STEP, NAME_TEXT, null, () -> {
            Object out = r.ok() ? r.value() : Map.of("error", String.valueOf(r.lastError()));
            int seq = store.addStep(NewStep.llm(caseId, STEP, NAME_TEXT, in, out, at, null, r.promptId(),
                    r.promptVersion(), r.model(), r.temperature(), code.value(), r.status().name(), r.attempts()));
            SpecificAnswerStep.linkCalls(jdbc, caseId, seq, r.callIds());
            return new TextResult(r.status(), r.ok() ? r.value() : null, List.of(seq), r.callIds(),
                    r.ok() ? null : r.lastError());
        });
    }
    public static final int MAX_PICKS = 5;

    public record Result(LlmResult.Status status, Answer answer, List<Integer> steps, List<Long> callIds, String error,
                         int inputChars, int listSize) {
        public boolean failed() {
            return status == LlmResult.Status.LLM_FAILED;
        }
    }

    private final LlmGateway llm;
    private final CaseStore store;
    private final CaseLock lock;
    private final JdbcClient jdbc;
    private final Knowledge knowledge;
    private final AppClock clock;
    private final CodeVersion code;
    private final JsonMapper json;

    public GeneralAnswerStep(LlmGateway llm, CaseStore store, CaseLock lock, JdbcClient jdbc, Knowledge knowledge,
                             AppClock clock, CodeVersion code, JsonMapper json) {
        this.llm = llm;
        this.store = store;
        this.lock = lock;
        this.jdbc = jdbc;
        this.knowledge = knowledge;
        this.clock = clock;
        this.code = code;
        this.json = json;
    }

    public Result run(CaseState st, ArticleList list, Instant deadline) {
        Map<String, Object> in = new LinkedHashMap<>();
        in.put("text", st.caseRow().content());
        in.put("articles", list.text());
        LlmResult<GeneralPicks> r = llm.call(PROMPT, in, GeneralPicks.class, out -> check(list, out),
                new CallContext(st.caseRow().caseId(), null, deadline, false));
        int chars = json.writeValueAsString(in).length();
        KnowledgeSnapshot k = knowledge.snapshot();
        Answer a = r.ok() ? answer(k, list, r.value()) : null;
        String caseId = st.caseRow().caseId();
        LocalDateTime at = clock.nowUtc();
        // 단계 input에는 목록 글 대신 판·크기만(목록은 스냅샷에서 같은 글로 다시 만든다: 기준일·꾸러미 판이 같으면 같음)
        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("text", st.caseRow().content());
        rec.put("articles", Map.of("date", String.valueOf(list.date()), "size", list.size(), "chars",
                list.text().length(), "sha256", io.github.overdozya.privacyask.knowledge.Hashes.sha256(list.text())));
        return lock.write(caseId, st.inputMark(), STEP, NAME, null, () -> {
            Object out = a != null ? a : Map.of("error", String.valueOf(r.lastError()));
            int seq = store.addStep(NewStep.llm(caseId, STEP, NAME, rec, out, at, null, r.promptId(), r.promptVersion(),
                    r.model(), r.temperature(), code.value(), r.status().name(), r.attempts()));
            SpecificAnswerStep.linkCalls(jdbc, caseId, seq, r.callIds());
            return new Result(r.status(), a, List.of(seq), r.callIds(), r.ok() ? null : r.lastError(), chars, list.size());
        });
    }

    /** 통과면 null. */
    static String check(ArticleList list, GeneralPicks out) {
        List<String> bad = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        if (out.picks().size() > MAX_PICKS) {
            bad.add("고른 조가 " + out.picks().size() + "개(상한 " + MAX_PICKS + ")");
        }
        for (GeneralPicks.Pick p : out.picks()) {
            if (!list.has(p.key())) {
                bad.add("키 '" + p.key() + "'는 목록 밖");
            }
            if (!seen.add(p.key())) {
                bad.add("키 '" + p.key() + "' 두 번");
            }
        }
        if (!out.noBasis() && out.picks().isEmpty()) {
            bad.add("고른 조가 없는데 no_basis가 false");
        }
        return bad.isEmpty() ? null : String.join("; ", bad.subList(0, Math.min(4, bad.size())));
    }

    static Answer answer(KnowledgeSnapshot k, ArticleList list, GeneralPicks out) {
        List<Answer.Pick> picks = new ArrayList<>();
        List<Answer.Source> sources = new ArrayList<>();
        List<Answer.Guide> guides = new ArrayList<>();
        Set<String> guideSeen = new LinkedHashSet<>();
        for (GeneralPicks.Pick p : out.picks()) {
            ArticleList.Entry e = list.get(p.key()).orElseThrow();
            picks.add(new Answer.Pick(e.key(), e.label(), e.title(), e.lawName(), p.why()));
            for (LawUnits.Unit u : list.units(p.key())) {
                LawUnits.At at = k.laws().at(u.unitKey(), list.date());
                Documents.Doc d = k.documents().get(u.docId()).orElseThrow();
                sources.add(new Answer.Source(u.unitKey(), PackageOpener.LAW_UNIT, d.title(), u.article(), u.title(), null,
                        u.textHash(), u.effectiveFrom() == null ? null : String.valueOf(u.effectiveFrom()),
                        at.upcoming().stream().map(x -> String.valueOf(x.effectiveFrom())).toList(), true, d.usePolicy(),
                        u.text()));
            }
            for (Packages.Package pkg : list.coreOf(p.key())) {
                for (Packages.Item it : pkg.items()) {
                    if (!PackageOpener.GUIDE_SECTION.equals(it.targetKind())
                            || (it.conds() != null && !it.conds().isEmpty()) || !guideSeen.add(it.targetKey())) {
                        continue;
                    }
                    GuideSections.Section s = k.sections().get(it.targetKey()).orElse(null);
                    if (s == null) {
                        continue;
                    }
                    String doc = k.documents().get(s.docId()).map(Documents.Doc::title).orElse(null);
                    guides.add(new Answer.Guide(s.sectionId(), pkg.packageId(), doc, s.pathText(), s.title(),
                            k.sections().hash(s.sectionId()).orElse(null)));
                }
            }
        }
        List<Answer.AddedReason> reasons = new ArrayList<>();
        if (out.noBasis() || picks.isEmpty()) {
            reasons.add(new Answer.AddedReason(Answer.NO_BASIS, null,
                    "답할 근거 조가 안B 목록에 없음(결정 13: 근거 없음 → 검토 안내)"));
        }
        boolean conclusive = reasons.isEmpty();
        return new Answer(Answer.GENERAL, null, "TODAY", String.valueOf(list.date()), false, conclusive, null, null,
                List.copyOf(picks), out.noBasis(), out.reason(), List.copyOf(sources), null, List.copyOf(guides), reasons,
                null, null);
    }
}
