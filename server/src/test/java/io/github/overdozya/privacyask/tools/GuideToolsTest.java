package io.github.overdozya.privacyask.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.utility.MountableFile;

import io.github.overdozya.privacyask.knowledge.Knowledge;
import io.github.overdozya.privacyask.knowledge.KnowledgeSnapshot;
import io.github.overdozya.privacyask.knowledge.LoadedDb;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A6 도구(S10 완료 기준 ②): 기동 때 벡터 적재 ms·힙, 질의 임베딩 ms·검색 ms(p50·최대), Java(ONNX) ↔ Python 질의 벡터 코사인,
 * 모델 없음 → 글자 검색만, read_section 해시 = 스냅샷 절 해시.
 * 공유 테스트 DB(적재 4개)와 따로 같은 컨테이너에 새 DB를 만들고 15_kb_embeddings.sql까지 넣는다. 적재 파일·Docker가 없으면 건너뛴다.
 * 모델 쪽 확인은 data/models/kure-v3-onnx(빌드 산출, git 밖)가 있을 때만. 질의 문장은 build/emb가 지은 일반 문장(query_check.json).
 * 잰 숫자는 data/out/emb/s10_java_check.json(git 밖)에 남긴다.
 */
class GuideToolsTest {
    static final Path EMB_SQL = LoadedDb.MYSQL_DIR.resolve("15_kb_embeddings.sql");
    static final Path MODEL = LoadedDb.DATA.resolve("models").resolve("kure-v3-onnx");
    static final Path OUT = LoadedDb.DATA.resolve("out").resolve("emb").resolve("s10_java_check.json");
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final String DB = "s10vec";

    static KnowledgeSnapshot snap;
    static GuideIndex index;
    static Knowledge knowledge;
    static final Map<String, Object> report = new LinkedHashMap<>();

    @BeforeAll
    static void load() throws Exception {
        assumeThat(LoadedDb.available() && Files.isRegularFile(EMB_SQL)).as("적재 파일·Docker·15_kb_embeddings.sql 없음").isTrue();
        LoadedDb.createLoaded(DB);
        var c = LoadedDb.container();
        c.copyFileToContainer(MountableFile.forHostPath(EMB_SQL), "/tmp/15_kb_embeddings.sql");
        ExecResult r = c.execInContainer("sh", "-c", "mysql -u" + c.getUsername() + " -p" + c.getPassword()
                + " --default-character-set=utf8mb4 " + DB + " < /tmp/15_kb_embeddings.sql");
        assertThat(r.getExitCode()).as(r.getStderr()).isZero();
        JdbcClient jdbc = JdbcClient.create(LoadedDb.dataSource(DB));
        snap = LoadedDb.snapshot();
        knowledge = Knowledge.of(snap);
        System.gc();
        index = GuideIndex.load(snap, jdbc);
        GuideIndex.Stats s = index.stats();
        report.put("index", Map.of("chunks", s.chunks(), "vectors", s.vectors(), "missing_vectors", s.missingVectors(),
                "text_terms", s.textTerms(), "load_ms", s.loadMs(),
                "heap_delta_mb", Math.round((s.heapAfterBytes() - s.heapBeforeBytes()) / 1048576.0)));
    }

    @AfterAll
    static void write() throws Exception {
        if (!report.isEmpty()) {
            Files.createDirectories(OUT.getParent());
            Files.writeString(OUT, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n");
            System.out.println("S10 도구 숫자: " + JSON.writeValueAsString(report));
        }
    }

    static ToolsProperties props() {
        return new ToolsProperties(true, 2, 3, 6000, null, false, false);
    }

    @Test
    void everyGuideChunkHasVector() {
        assertThat(index.vectors()).isEqualTo(index.size()).isEqualTo(3925);
    }

    /** 모델 없음 → 글자 검색만(1건). */
    @Test
    void noModelTextOnly() {
        GuideTools t = new GuideTools(knowledge, index, OnnxQueryEmbedder.of(Path.of("/nonexistent-model-dir")), props());
        GuideTools.SearchResult r = t.search("개인정보 유출 신고 기한", null, 3, Set.of());
        assertThat(r.mode()).isEqualTo(GuideTools.TEXT_ONLY);
        assertThat(r.note()).contains("질의 모델 파일 없음");
        assertThat(r.hits()).hasSize(3);
        assertThat(r.hits()).allSatisfy(h -> {
            assertThat(h.vectorRank()).isNull();
            assertThat(h.textRank()).isNotNull();
        });
        report.put("text_only", Map.of("mode", r.mode(), "hits", r.hits().stream().map(GuideTools.Hit::sectionId).toList(),
                "search_ms", r.searchMs()));
    }

    /** 벡터 없는 색인(적재 전) → 글자 검색만, 까닭 기록. */
    @Test
    void noVectorsTextOnly() {
        GuideIndex bare = GuideIndex.load(snap, LoadedDb.jdbc());   // 공유 DB에는 15 적재 파일이 없다
        assertThat(bare.vectors()).isZero();
        GuideTools t = new GuideTools(knowledge, bare, QueryEmbedder.NONE, props());
        GuideTools.SearchResult r = t.search("가명정보 제3자 제공", "가명처리", 2, Set.of());
        assertThat(r.mode()).isEqualTo(GuideTools.TEXT_ONLY);
        assertThat(r.note()).contains("벡터 없음");
        assertThat(r.hits()).hasSize(2);
    }

    @Test
    void readSectionHashAndExclude() {
        GuideTools t = new GuideTools(knowledge, index, QueryEmbedder.NONE, props());
        GuideTools.SearchResult r = t.search("CCTV 영상 제공", null, 3, Set.of());
        String id = r.hits().get(0).sectionId();
        GuideTools.Section s = t.read(id).orElseThrow();
        assertThat(s.textHash()).isEqualTo(snap.sections().hash(id).orElseThrow());
        assertThat(s.text()).isNotBlank();
        assertThat(s.chars()).isGreaterThanOrEqualTo(s.text().length());
        GuideTools.SearchResult again = t.search("CCTV 영상 제공", null, 3, Set.of(id));
        assertThat(again.hits()).extracting(GuideTools.Hit::sectionId).doesNotContain(id);
        assertThat(t.read("없는-절")).isEmpty();
        // 큰 절은 앞부분만
        GuideTools small = new GuideTools(knowledge, index, QueryEmbedder.NONE, new ToolsProperties(true, 2, 3, 50, null,
                false, false));
        GuideTools.Section cut = small.read(id).orElseThrow();
        assertThat(cut.text()).hasSizeLessThanOrEqualTo(50);
        assertThat(cut.truncated()).isEqualTo(s.chars() > 50);
        assertThat(cut.textHash()).isEqualTo(s.textHash());
    }

    /** Java ONNX 질의 벡터 ↔ Python(sentence-transformers float32, 같은 int8 ONNX를 Python으로) 코사인, 임베딩·검색 ms. */
    @Test
    void javaQueryVectorsAndLatency() throws Exception {
        Path check = MODEL.resolve("query_check.json");
        assumeThat(Files.isRegularFile(MODEL.resolve(OnnxQueryEmbedder.MODEL)) && Files.isRegularFile(check))
                .as("질의 모델 없음(build/emb/export_onnx.py 전)").isTrue();
        OnnxQueryEmbedder m = new OnnxQueryEmbedder(MODEL);
        long loadMs = m.load();
        GuideTools t = new GuideTools(knowledge, index, m, props());
        JsonNode q = JSON.readTree(check.toFile());
        List<Double> cosSt = new ArrayList<>(), cosPy = new ArrayList<>();
        List<Long> embedMs = new ArrayList<>(), searchMs = new ArrayList<>();
        int sameTop = 0, n = 0;
        for (JsonNode x : q.get("queries")) {
            String text = x.get("text").asString();
            float[] v = m.embed(text);
            cosSt.add(cos(v, x.get("st")));
            cosPy.add(cos(v, x.get("onnx_py")));
            GuideTools.SearchResult r = t.search(text, null, 3, Set.of());
            assertThat(r.mode()).isEqualTo(GuideTools.HYBRID);
            embedMs.add(r.embedMs());
            searchMs.add(r.searchMs());
            // 벡터만으로 본 1등 청크가 Python float32 질의 벡터의 1등과 같은가
            float[] st = new float[QueryEmbedder.DIM];
            for (int d = 0; d < st.length; d++) {
                st[d] = (float) x.get("st").get(d).asDouble();
            }
            sameTop += index.nearest(v, 1).get(0).chunk() == index.nearest(st, 1).get(0).chunk() ? 1 : 0;
            n++;
        }
        m.close();
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("model_load_ms", loadMs);
        e.put("cos_java_vs_python_onnx_int8", stats(cosPy));
        e.put("cos_java_int8_vs_python_st_float32", stats(cosSt));
        e.put("same_top1_chunk_vs_st_float32", sameTop + "/" + n);
        e.put("embed_ms", pct(embedMs));
        e.put("search_ms", pct(searchMs));
        report.put("query", e);
        assertThat(stats(cosPy).get("min")).isGreaterThan(0.999);   // 같은 모델·같은 토큰이면 거의 1
        assertThat(stats(cosSt).get("min")).isGreaterThan(0.95);
    }

    static double cos(float[] v, JsonNode ref) {
        double dot = 0, a = 0, b = 0;
        for (int d = 0; d < v.length; d++) {
            double r = ref.get(d).asDouble();
            dot += v[d] * r;
            a += v[d] * v[d];
            b += r * r;
        }
        return dot / Math.sqrt(a * b);
    }

    static Map<String, Double> stats(List<Double> xs) {
        List<Double> s = xs.stream().sorted().toList();
        return Map.of("min", round(s.get(0)), "mean", round(s.stream().mapToDouble(Double::doubleValue).average().orElse(0)),
                "max", round(s.get(s.size() - 1)));
    }

    static Map<String, Long> pct(List<Long> xs) {
        List<Long> s = xs.stream().sorted().toList();
        return Map.of("p50", s.get(s.size() / 2), "max", s.get(s.size() - 1));
    }

    static double round(double x) {
        return Math.round(x * 1e6) / 1e6;
    }
}
