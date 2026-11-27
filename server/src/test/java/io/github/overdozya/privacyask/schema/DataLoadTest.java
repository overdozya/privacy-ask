package io.github.overdozya.privacyask.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.MountableFile;

import io.github.overdozya.privacyask.knowledge.Knowledge;
import io.github.overdozya.privacyask.knowledge.KnowledgeLoader;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 레포 밖 적재 파일 4개(data/out/mysql)가 V1~V7 스키마에 그대로 들어가는지(06 2.5, 7절 S2 ③④).
 * 파일이 없으면(공개 레포만 받은 경우) 건너뛴다.
 */
@SpringBootTest(properties = { "app.llm.provider=fake" })
@Testcontainers(disabledWithoutDocker = true)
class DataLoadTest {
    static final Path DIR = Path.of(System.getProperty("privacyask.data-dir", "../data"), "out", "mysql");
    static final List<String> FILES = List.of("10_kb", "20_packages", "30_ledger", "40_case_catalog");

    @Container
    @ServiceConnection
    static MySQLContainer mysql = MySqlContainers.mysql84();

    @Autowired JdbcClient jdbc;

    @Test
    void loadFilesTwiceAndCountsMatchManifest() throws Exception {
        assumeThat(Files.isRegularFile(DIR.resolve("manifest.json"))).as("적재 파일 없음: " + DIR).isTrue();
        // 두 번 돌려도 같은 결과(파일마다 지우고 다시 넣음)
        for (int round = 0; round < 2; round++) {
            for (String f : FILES) {
                mysql.copyFileToContainer(MountableFile.forHostPath(DIR.resolve(f + ".sql")), "/tmp/" + f + ".sql");
                ExecResult r = mysql.execInContainer("sh", "-c",
                        "mysql -u" + mysql.getUsername() + " -p" + mysql.getPassword()
                                + " --default-character-set=utf8mb4 " + mysql.getDatabaseName() + " < /tmp/" + f + ".sql");
                assertThat(r.getExitCode()).as(f + ": " + r.getStderr()).isZero();
                assertThat(r.getStdout()).as(f).doesNotContain("MISMATCH").contains("OK");
            }
        }
        JsonNode manifest = JsonMapper.builder().build().readTree(DIR.resolve("manifest.json").toFile());
        long expected = 0, actual = 0;
        for (var e : manifest.path("tables").properties()) {
            long n = jdbc.sql("SELECT COUNT(*) FROM `" + e.getKey() + "`").query(Long.class).single();
            assertThat(n).as(e.getKey()).isEqualTo(e.getValue().path("rows").asLong());
            expected += e.getValue().path("rows").asLong();
            actual += n;
        }
        assertThat(actual).isEqualTo(expected);
        // 기동 검사(행 수 = manifest·참조, 06 3.1)는 지식 스냅샷이 한다
        JsonMapper json = JsonMapper.builder().build();
        Knowledge k = Knowledge.load(new KnowledgeLoader(jdbc, json), DIR.resolve("manifest.json"), json);
        assertThat(k.isLoaded()).isTrue();
        assertThat(k.snapshot().stats().totalRows()).isEqualTo(expected);
        // 해시 대상 글이 그대로 들어갔는지 하나: sha256(text) = text_hash
        assertThat(jdbc.sql("SELECT COUNT(*) FROM kb_chunks WHERE SHA2(text, 256) <> text_hash").query(Long.class).single())
                .isZero();
    }
}
