package io.github.overdozya.privacyask.record;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.overdozya.privacyask.knowledge.LoadedDb;
import io.github.overdozya.privacyask.record.CaseInput.CitationEntry;
import io.github.overdozya.privacyask.record.CaseInput.IssueEntry;
import io.github.overdozya.privacyask.record.CaseInput.NewJudgment;
import io.github.overdozya.privacyask.record.CaseInput.SlotEntry;
import io.github.overdozya.privacyask.record.CaseInput.Value;
import tools.jackson.databind.JsonNode;

/**
 * 예시 사안(data/수집/예시사안.json, 우리가 지은 사안)을 CaseStore로 넣는다(store.py load_events를 옮김).
 * case_id만 바꿔 여러 번 넣을 수 있다. 판정 이벤트는 judgment()로 따로 만들어 고친 판을 거부 시험에 쓴다.
 */
final class ExampleCase {
    static final Path FILE = LoadedDb.DATA.resolve("수집").resolve("예시사안.json");
    static final Path CASE_DB = LoadedDb.DATA.resolve("out").resolve("case").resolve("case.db");
    private static final DateTimeFormatter AT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    final JsonNode spec;

    ExampleCase() throws Exception {
        this.spec = LoadedDb.JSON.readTree(Files.readString(FILE));
    }

    static boolean available() {
        return Files.isRegularFile(FILE) && LoadedDb.available();
    }

    static LocalDateTime at(JsonNode n) {
        return LocalDateTime.parse(n.asString(), AT);
    }

    static String str(JsonNode n, String k) {
        JsonNode v = n.get(k);
        return v == null || v.isNull() ? null : v.asString();
    }

    static Integer num(JsonNode n, String k) {
        JsonNode v = n.get(k);
        return v == null || v.isNull() ? null : v.asInt();
    }

    static List<String> strings(JsonNode arr) {
        List<String> out = new ArrayList<>();
        if (arr != null) {
            arr.forEach(x -> out.add(x.asString()));
        }
        return out;
    }

    /** 판정 직전까지(includeJudgment=false) 또는 끝까지 넣는다. */
    void load(CaseStore store, String caseId, boolean includeJudgment) {
        for (JsonNode ev : spec.get("events")) {
            String t = ev.get("type").asString();
            switch (t) {
                case "case" -> store.addCase(new CaseInput.NewCase(caseId, str(ev, "requester_id"), str(ev, "content"),
                        str(ev, "service_name"), str(ev, "rule_set_version"), str(ev, "package_version"),
                        str(ev, "ledger_version"), at(ev.get("at"))));
                case "case_update" -> {
                    Map<String, Object> set = new LinkedHashMap<>();
                    for (var e : ev.get("set").properties()) {
                        String key = e.getKey();
                        JsonNode v = e.getValue();
                        set.put(key, "reference_date".equals(key) ? LocalDate.parse(v.asString())
                                : "closed_at".equals(key) ? at(v) : v.isNull() ? null : v.asString());
                    }
                    store.updateCase(caseId, at(ev.get("at")), set);
                }
                case "step" -> {
                    int seq = store.addStep(new CaseInput.NewStep(caseId, str(ev, "step"), str(ev, "actor"),
                            str(ev, "name"), ev.get("input"), ev.get("output"), at(ev.get("at")), num(ev, "flow_no"),
                            str(ev, "prompt_id"), str(ev, "prompt_version"), str(ev, "model_name"),
                            ev.has("temperature") ? new BigDecimal(ev.get("temperature").asString()) : null,
                            str(ev, "code_version"), ev.has("status") ? str(ev, "status") : "OK",
                            ev.has("attempts") ? ev.get("attempts").asInt() : 1));
                    JsonNode out = ev.get("output");
                    for (JsonNode fl : out.path("flows")) {
                        store.addFlow(new CaseInput.NewFlow(caseId, fl.get("flow").asInt(), str(fl, "label"),
                                str(fl, "origin"), str(fl, "destination"), str(fl, "service_name"), seq));
                    }
                    for (JsonNode f : out.path("facts")) {
                        List<Integer> targets = new ArrayList<>();
                        JsonNode flow = f.get("flow");
                        if (flow != null && flow.isArray()) {
                            flow.forEach(x -> targets.add(x.asInt()));
                        } else {
                            targets.add(flow == null || flow.isNull() ? null : flow.asInt());
                        }
                        List<Value> values = new ArrayList<>();
                        for (JsonNode v : f.get("values")) {
                            values.add(v.isArray() ? new Value(v.get(0).asString(), v.get(1).asString())
                                    : Value.of(v.asString()));
                        }
                        for (Integer fl : targets) {
                            store.addFact(new CaseInput.NewFact(caseId, str(f, "fact"), values, str(f, "status"),
                                    str(f, "source"), seq, at(ev.get("at")), fl, num(f, "turn"), str(f, "quote"),
                                    strings(f.get("rows")), str(f, "ledger_version"), str(f, "note")));
                        }
                    }
                }
                case "turn" -> store.addTurn(new CaseInput.NewTurn(caseId, str(ev, "role"), str(ev, "kind"),
                        str(ev, "text"), strings(ev.get("choices")), ev.get("step_seq").asInt(), at(ev.get("at")),
                        num(ev, "flow_no"), str(ev, "fact_id"), num(ev, "ask_no"), num(ev, "reply_to")));
                case "judgment" -> {
                    if (includeJudgment) {
                        store.addJudgment(judgment(store, caseId));
                    }
                }
                default -> throw new IllegalArgumentException(t);
            }
        }
    }

    JsonNode judgmentEvent() {
        for (JsonNode ev : spec.get("events")) {
            if ("judgment".equals(ev.get("type").asString())) {
                return ev;
            }
        }
        throw new IllegalStateException("판정 이벤트 없음");
    }

    /** 예시 판정 → NewJudgment. '기준표'는 지금 대응표 해시, basis_facts 사실 ID는 지금 판 fact_row_id로. */
    NewJudgment judgment(CaseStore store, String caseId) {
        JsonNode j = judgmentEvent();
        String fm = str(j, "fact_map_sha256");
        if ("기준표".equals(fm)) {
            fm = LoadedDb.snapshot().catalog().factMapSha256();
        }
        List<SlotEntry> slots = new ArrayList<>();
        for (JsonNode s : j.get("slots")) {
            int flow = s.get("flow").asInt();
            List<Long> basis = new ArrayList<>();
            for (JsonNode f : s.get("basis_facts")) {
                basis.add(store.currentFactRow(caseId, flow, f.asString())
                        .orElseThrow(() -> new Rejected("C16", "흐름 " + flow + "에 " + f.asString() + " 사실 없음")));
            }
            slots.add(new SlotEntry(flow, str(s, "slot"), str(s, "status"), strings(s.get("values")), basis,
                    strings(s.get("unknown_facts"))));
        }
        List<IssueEntry> issues = new ArrayList<>();
        for (JsonNode x : j.get("issues")) {
            issues.add(new IssueEntry(x.get("flow").asInt(), str(x, "issue"), str(x, "package_id"), str(x, "grade"),
                    strings(x.get("rule_ids")), strings(x.get("conditions")), strings(x.get("interp_flags")),
                    x.get("conclusive").asBoolean(), str(x, "opinion"), str(x, "actions"), x.get("visible").asBoolean()));
        }
        List<CitationEntry> cits = new ArrayList<>();
        for (JsonNode c : j.get("citations")) {
            cits.add(new CitationEntry(num(c, "issue"), str(c, "kind"), str(c, "key"), str(c, "text_hash"),
                    str(c, "ledger_version"), str(c, "origin")));
        }
        List<CaseInput.PromptRef> prompts = new ArrayList<>();
        j.get("prompt_versions").forEach(p -> prompts.add(new CaseInput.PromptRef(str(p, "prompt_id"), str(p, "version"))));
        return new NewJudgment(caseId, j.get("step_seq").asInt(), num(j, "judged_turn"),
                LocalDate.parse(str(j, "reference_date")), str(j, "rule_set_version"), str(j, "package_version"),
                str(j, "ledger_version"), fm, str(j, "model_name"), new BigDecimal(j.get("temperature").asString()),
                prompts, str(j, "case_grade"), str(j, "exit_type"), strings(j.get("exit_reasons")),
                j.get("conclusive").asBoolean(), j.get("flags"), j.get("review_bundle"), str(j, "premise_note"),
                at(j.get("created_at")), slots, issues, cits);
    }
}
