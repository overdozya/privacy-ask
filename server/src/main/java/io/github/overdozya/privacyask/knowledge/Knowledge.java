package io.github.overdozya.privacyask.knowledge;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 스냅샷을 든 곳(빈 하나). 기동 때 만든다: 적재 전(지식 21테이블이 모두 0행)이면 경고만 하고 비워 두며,
 * 그 밖에 기동 검사를 하나라도 어기면 KnowledgeLoadException으로 기동을 멈춘다(06 3.1).
 */
public final class Knowledge {
    private static final Logger log = LoggerFactory.getLogger(Knowledge.class);

    public enum State { LOADED, EMPTY }

    private final KnowledgeSnapshot snapshot;
    private final State state;

    private Knowledge(KnowledgeSnapshot snapshot, State state) {
        this.snapshot = snapshot;
        this.state = state;
    }

    public static Knowledge of(KnowledgeSnapshot snapshot) {
        return new Knowledge(snapshot, State.LOADED);
    }

    public static Knowledge empty() {
        return new Knowledge(null, State.EMPTY);
    }

    /** manifest: 없으면 행 수 비교를 건너뛴다(경고). */
    public static Knowledge load(KnowledgeLoader loader, Path manifest, JsonMapper json) {
        Map<String, Long> expected = readManifest(manifest, json);
        Map<String, Long> counts = loader.countRows();
        if (counts.values().stream().allMatch(n -> n == 0)) {
            log.warn("지식 테이블 {}개가 모두 비어 있다(적재 전). 스냅샷 없이 띄운다. 적재 뒤 다시 띄운다", counts.size());
            return empty();
        }
        KnowledgeSnapshot s = loader.load();
        if (expected == null) {
            log.warn("manifest 없음({}): 행 수 비교를 건너뛴다", manifest);
        }
        List<String> bad = KnowledgeCheck.check(s, expected);
        if (!bad.isEmpty()) {
            throw new KnowledgeLoadException(bad);
        }
        var st = s.stats();
        log.info("지식 스냅샷 적재 {} ms, {}테이블 {}행{}, 힙 {} MB → {} MB(+{} MB). 꾸러미 {}, 규칙 세트 {}, 슬롯 변환 {}, 원장 {}",
                st.loadMs(), st.rows().size(), st.totalRows(), expected == null ? "" : " = manifest",
                mb(st.heapBeforeBytes()), mb(st.heapAfterBytes()), mb(st.heapAfterBytes() - st.heapBeforeBytes()),
                s.packages().currentVersion(), s.ruleSet().version(), s.slotRules().version(),
                s.versions().ledgerVersions());
        return of(s);
    }

    static Map<String, Long> readManifest(Path manifest, JsonMapper json) {
        if (manifest == null || !Files.isRegularFile(manifest)) {
            return null;
        }
        JsonNode root = json.readTree(manifest.toFile());
        Map<String, Long> out = new LinkedHashMap<>();
        for (var e : root.path("tables").properties()) {
            out.put(e.getKey(), e.getValue().path("rows").asLong());
        }
        return out;
    }

    private static long mb(long bytes) {
        return Math.round(bytes / 1024.0 / 1024.0);
    }

    public State state() {
        return state;
    }

    public boolean isLoaded() {
        return state == State.LOADED;
    }

    /** 적재 전이면 IllegalStateException. */
    public KnowledgeSnapshot snapshot() {
        if (snapshot == null) {
            throw new IllegalStateException("지식이 적재되지 않았다: data/out/mysql 적재 뒤 서버를 다시 띄운다");
        }
        return snapshot;
    }
}
