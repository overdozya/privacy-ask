package io.github.overdozya.privacyask.common;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 기동 때 지식 테이블 행 수를 data/out/mysql/manifest.json과 견준다(06 7절 S2 ④).
 * 전부 0행이면 "아직 적재 전"으로 보고 경고만 한다(첫 기동 = Flyway만). 일부만 다르면 기동을 멈춘다(적재를 잊거나 일부만 넣은 것).
 * 지식 스냅샷 전체(참조·판 검사)는 S4의 KnowledgeSnapshot이 맡는다.
 */
@Component
@ConditionalOnProperty(name = "app.knowledge.row-check", havingValue = "true", matchIfMissing = true)
public class LoadedRowCheck implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(LoadedRowCheck.class);

    private final JdbcClient jdbc;
    private final Path manifest;

    public LoadedRowCheck(JdbcClient jdbc, @Value("${app.data-dir:../data}") String dataDir) {
        this.jdbc = jdbc;
        this.manifest = Path.of(dataDir, "out", "mysql", "manifest.json");
    }

    @Override
    public void run(ApplicationArguments args) {
        Result r = check();
        switch (r.state()) {
            case NO_MANIFEST -> log.warn("행 수 확인 건너뜀: manifest 없음 ({})", manifest);
            case EMPTY -> log.warn("지식 테이블이 비어 있다(적재 전). {}개 테이블, 기대 {}행. 적재 뒤 다시 띄운다", r.tables(), r.expectedTotal());
            case OK -> log.info("행 수 확인 OK: {}개 테이블 {}행 = manifest ({} ms)", r.tables(), r.actualTotal(), r.elapsedMs());
            case MISMATCH -> throw new IllegalStateException("적재 행 수가 manifest와 다르다: " + r.mismatches());
        }
    }

    public enum State { NO_MANIFEST, EMPTY, OK, MISMATCH }

    public record Result(State state, int tables, long expectedTotal, long actualTotal, List<String> mismatches, long elapsedMs) {}

    public Result check() {
        long t0 = System.nanoTime();
        if (!Files.isRegularFile(manifest)) {
            return new Result(State.NO_MANIFEST, 0, 0, 0, List.of(), 0);
        }
        JsonNode root = JsonMapper.builder().build().readTree(manifest.toFile());
        Map<String, Long> expected = new TreeMap<>();
        for (Map.Entry<String, JsonNode> e : root.path("tables").properties()) {
            expected.put(e.getKey(), e.getValue().path("rows").asLong());
        }
        long expTotal = 0, actTotal = 0;
        List<String> bad = new ArrayList<>();
        for (Map.Entry<String, Long> e : expected.entrySet()) {
            String table = e.getKey();
            if (!table.matches("[a-z_]+")) {
                bad.add(table + ": 이름이 이상함");
                continue;
            }
            Long n;
            try {
                n = jdbc.sql("SELECT COUNT(*) FROM `" + table + "`").query(Long.class).single();
            } catch (RuntimeException ex) {
                bad.add(table + ": 테이블 없음");
                continue;
            }
            expTotal += e.getValue();
            actTotal += n;
            if (n.longValue() != e.getValue().longValue()) {
                bad.add(table + " " + n + "/" + e.getValue());
            }
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        State state;
        if (bad.isEmpty()) {
            state = State.OK;
        } else if (actTotal == 0 && bad.stream().noneMatch(s -> s.endsWith("없음") || s.endsWith("이상함"))) {
            state = State.EMPTY;
        } else {
            state = State.MISMATCH;
        }
        return new Result(state, expected.size(), expTotal, actTotal, bad, ms);
    }
}
