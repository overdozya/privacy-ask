package io.github.overdozya.privacyask.issues;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.github.overdozya.privacyask.knowledge.Catalog;
import io.github.overdozya.privacyask.knowledge.KnowledgeSnapshot;
import io.github.overdozya.privacyask.knowledge.LoadedDb;
import io.github.overdozya.privacyask.rules.Condition;
import io.github.overdozya.privacyask.rules.EvalContext;
import io.github.overdozya.privacyask.rules.FactState;
import tools.jackson.databind.JsonNode;

/**
 * 06 7절 S5 ①: S3 Python 기준 평가기가 낸 벡터(data/out/case/slot_rules_vectors.json) 전부를 Java SlotConverter가
 * expect 전체(상태·값·근거 사실·미확인·열림·표시·fired)와 같게 낸다. 규칙·기준표는 적재 DB의 스냅샷에서 읽는다.
 * 데이터 파일이나 Docker가 없으면(공개 레포만 받은 경우) 건너뛴다(06 3.6).
 */
class SlotRulesVectorTest {
    static final Path FILE = LoadedDb.DATA.resolve("out").resolve("case").resolve("slot_rules_vectors.json");
    static KnowledgeSnapshot snap;
    static JsonNode doc;

    @BeforeAll
    static void load() throws Exception {
        assumeThat(Files.isRegularFile(FILE) && LoadedDb.available()).as("벡터 파일·적재 파일 또는 Docker 없음").isTrue();
        snap = LoadedDb.snapshot();
        doc = LoadedDb.JSON.readTree(Files.readString(FILE));
    }

    /** 벡터 입력 → 흐름 사실. ledger_rows는 {행 ID: {칸: 글}}. */
    static FlowFacts flowFacts(JsonNode v) {
        Map<String, FactState> facts = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> e : v.get("facts").properties()) {
            JsonNode f = e.getValue();
            facts.put(e.getKey(), new FactState(f.get("status").asString(), Condition.strings(f.get("values")),
                    Condition.strings(f.get("rows")), null));
        }
        Map<String, List<EvalContext.Column>> led = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> e : v.get("ledger_rows").properties()) {
            List<EvalContext.Column> cols = new ArrayList<>();
            for (Map.Entry<String, JsonNode> c : e.getValue().properties()) {
                cols.add(new EvalContext.Column(c.getKey(), c.getValue().asString()));
            }
            led.put(e.getKey(), cols);
        }
        return new FlowFacts(1, facts, led::get);
    }

    static Map<String, Object> asMap(SlotResult r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", r.status());
        m.put("values", r.values());
        m.put("basis_facts", r.basisFacts());
        m.put("unknown_facts", r.unknownFacts());
        m.put("open_facts", r.openFacts());
        m.put("flags", r.flags());
        m.put("fired", r.fired());
        return m;
    }

    static Map<String, Object> expected(JsonNode e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", e.get("status").asString());
        m.put("values", Condition.strings(e.get("values")));
        m.put("basis_facts", Condition.strings(e.get("basis_facts")));
        m.put("unknown_facts", Condition.strings(e.get("unknown_facts")));
        m.put("open_facts", Condition.strings(e.get("open_facts")));
        m.put("flags", Condition.strings(e.get("flags")));
        List<Integer> fired = new ArrayList<>();
        e.get("fired").forEach(x -> fired.add(x.asInt()));
        m.put("fired", fired);
        return m;
    }

    @Test
    void sameRulesAndCatalogAsVectors() {
        assertThat(doc.get("fact_map_sha256").asString()).isEqualTo(snap.catalog().factMapSha256());
        assertThat(doc.get("rules").get("sha256").asString())
                .isEqualTo(snap.catalog().source(Catalog.SLOT_RULES).orElseThrow().sha256());
        assertThat(SlotConverter.of(snap.catalog(), snap.slotRules()).order())
                .containsExactlyElementsOf(Condition.strings(doc.get("slot_order")));
    }

    @Test
    void allVectorsPass() {
        SlotConverter conv = SlotConverter.of(snap.catalog(), snap.slotRules());
        int pass = 0;
        List<String> bad = new ArrayList<>();
        for (JsonNode v : doc.get("vectors")) {
            Map<String, SlotResult> got = conv.convert(flowFacts(v));
            boolean ok = true;
            for (Map.Entry<String, JsonNode> e : v.get("expect").properties()) {
                Map<String, Object> want = expected(e.getValue());
                Map<String, Object> g = asMap(got.get(e.getKey()));
                if (!want.equals(g)) {
                    ok = false;
                    bad.add(v.get("id").asString() + " " + e.getKey() + ": 기대 " + want + " / 결과 " + g);
                }
            }
            assertThat(got.keySet()).hasSize(v.get("expect").size());
            if (ok) {
                pass++;
            }
        }
        System.out.println("슬롯 변환 벡터 통과 " + pass + "/" + doc.get("count").asInt());
        assertThat(bad).isEmpty();
        assertThat(pass).isEqualTo(doc.get("count").asInt()).isEqualTo(doc.get("vectors").size());
    }
}
