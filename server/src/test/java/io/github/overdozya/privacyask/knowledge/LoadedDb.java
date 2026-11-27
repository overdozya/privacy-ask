package io.github.overdozya.privacyask.knowledge;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.MountableFile;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import tools.jackson.databind.json.JsonMapper;

/**
 * 테스트용 MySQL 8.4 하나(테스트 JVM 동안 공유): Flyway V1~ + 레포 밖 적재 파일 4개. 로컬 privacyask DB는 쓰지 않는다.
 * 적재 파일이 없거나(공개 레포만 받은 경우) Docker가 없으면 available()이 false → 테스트는 건너뛴다.
 * 다른 DB(createLoaded)는 같은 컨테이너 안에 새로 만든다(깨진 참조 시험용).
 */
public final class LoadedDb {
    public static final Path DATA = Path.of(System.getProperty("privacyask.data-dir", "../data"));
    public static final Path MYSQL_DIR = DATA.resolve("out").resolve("mysql");
    public static final List<String> FILES = List.of("10_kb", "20_packages", "30_ledger", "40_case_catalog");
    public static final JsonMapper JSON = JsonMapper.builder().build();

    private static MySQLContainer container;
    private static HikariDataSource main;
    private static KnowledgeSnapshot snapshot;

    private LoadedDb() {}

    public static boolean available() {
        return Files.isRegularFile(MYSQL_DIR.resolve("manifest.json")) && DockerClientFactory.instance().isDockerAvailable();
    }

    public static synchronized MySQLContainer container() {
        if (container == null) {
            container = new MySQLContainer("mysql:8.4")
                    .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_0900_ai_ci",
                            "--default-time-zone=+00:00");
            container.start();
            for (String f : FILES) {
                container.copyFileToContainer(MountableFile.forHostPath(MYSQL_DIR.resolve(f + ".sql")), "/tmp/" + f + ".sql");
            }
            migrateAndLoad(container.getDatabaseName());
        }
        return container;
    }

    /** 적재까지 끝난 기본 DB. */
    public static synchronized DataSource dataSource() {
        if (main == null) {
            main = dataSource(container().getDatabaseName());
        }
        return main;
    }

    public static JdbcClient jdbc() {
        return JdbcClient.create(dataSource());
    }

    /** 기본 DB에서 한 번 읽은 스냅샷(검사 통과 확인 포함). */
    public static synchronized KnowledgeSnapshot snapshot() {
        if (snapshot == null) {
            snapshot = Knowledge.load(new KnowledgeLoader(jdbc(), JSON), MYSQL_DIR.resolve("manifest.json"), JSON).snapshot();
        }
        return snapshot;
    }

    public static String url(String db) {
        MySQLContainer c = container();
        return "jdbc:mysql://" + c.getHost() + ":" + c.getMappedPort(3306) + "/" + db;
    }

    public static HikariDataSource dataSource(String db) {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(url(db));
        cfg.setUsername(container().getUsername());
        cfg.setPassword(container().getPassword());
        cfg.setMaximumPoolSize(4);
        cfg.addDataSourceProperty("connectionTimeZone", "UTC");
        cfg.addDataSourceProperty("forceConnectionTimeZoneToSession", "true");
        cfg.addDataSourceProperty("characterEncoding", "UTF-8");
        return new HikariDataSource(cfg);
    }

    /** 같은 컨테이너에 새 DB를 만들고 V1~ + 적재 4개를 넣는다. */
    public static synchronized void createLoaded(String db) throws Exception {
        root("CREATE DATABASE " + db + "; GRANT ALL ON " + db + ".* TO '" + container().getUsername() + "'@'%';");
        migrateAndLoad(db);
    }

    private static void migrateAndLoad(String db) {
        MySQLContainer c = container;
        Flyway.configure().dataSource(url(db), c.getUsername(), c.getPassword())
                .locations("classpath:db/migration").load().migrate();
        for (String f : FILES) {
            exec("mysql -u" + c.getUsername() + " -p" + c.getPassword() + " --default-character-set=utf8mb4 " + db
                    + " < /tmp/" + f + ".sql");
        }
    }

    public static void root(String sql) throws Exception {
        exec("mysql -uroot -p" + container().getPassword() + " -e \"" + sql.replace("\"", "\\\"") + "\"");
    }

    private static void exec(String sh) {
        try {
            ExecResult r = container.execInContainer("sh", "-c", sh);
            if (r.getExitCode() != 0) {
                throw new IllegalStateException(sh + " → " + r.getStderr());
            }
        } catch (java.io.IOException | InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }
}
