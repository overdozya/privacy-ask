package io.github.overdozya.privacyask.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assumptions.assumeThat;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.jdbc.core.simple.JdbcClient;

import io.github.overdozya.privacyask.PrivacyAskApplication;

/**
 * 06 7절 S4 ①: 실제 기동(스프링 컨텍스트)에서 스냅샷을 만들고, 빠진 참조 하나를 일부러 넣은 DB에서는 기동이 멈춘다.
 * 깨진 DB는 같은 테스트 컨테이너 안의 별도 DB(pa_broken)다. 로컬 privacyask DB는 건드리지 않는다.
 */
class KnowledgeStartupTest {

    @BeforeAll
    static void need() {
        assumeThat(LoadedDb.available()).as("적재 파일 또는 Docker 없음").isTrue();
    }

    private static ConfigurableApplicationContext start(String db) {
        // 명령줄 인자로 넘긴다(application.yml의 ${DB_URL:…}보다 앞서게. builder.properties는 가장 낮은 순위라 로컬 DB로 붙는다)
        return new SpringApplicationBuilder(PrivacyAskApplication.class)
                .web(WebApplicationType.NONE)
                .run("--spring.datasource.url=" + LoadedDb.url(db),
                        "--spring.datasource.username=" + LoadedDb.container().getUsername(),
                        "--spring.datasource.password=" + LoadedDb.container().getPassword(),
                        "--app.llm.provider=fake", "--app.data-dir=" + LoadedDb.DATA);
    }

    @Test
    void startsWithLoadedSnapshot() {
        LoadedDb.dataSource();
        try (ConfigurableApplicationContext ctx = start(LoadedDb.container().getDatabaseName())) {
            assertThat(ctx.getEnvironment().getProperty("spring.datasource.url")).isEqualTo(LoadedDb.url(LoadedDb.container().getDatabaseName()));
            Knowledge k = ctx.getBean(Knowledge.class);
            assertThat(k.isLoaded()).isTrue();
            assertThat(k.snapshot().stats().totalRows()).isEqualTo(LoadedDb.snapshot().stats().totalRows());
        }
    }

    @Test
    void missingReferenceStopsStartup() throws Exception {
        LoadedDb.createLoaded("pa_broken");
        try (var ds = LoadedDb.dataSource("pa_broken")) {
            JdbcClient j = JdbcClient.create(ds);
            // 행 수는 그대로 두고 꾸러미 단위 하나가 없는 조문 청크를 가리키게 한다(적재 파일 사이 FK가 없어 DB는 받는다)
            int n = j.sql("UPDATE package_item_units SET chunk_id = 99999999 WHERE item_id = 1 AND ord = 1").update();
            assertThat(n).isEqualTo(1);
        }
        assertThatThrownBy(() -> start("pa_broken").close())
                .satisfies(e -> {
                    Throwable root = NestedExceptionUtils.getMostSpecificCause(e);
                    assertThat(root).isInstanceOf(KnowledgeLoadException.class);
                    assertThat(((KnowledgeLoadException) root).problems())
                            .containsExactly("v1 PKG01 항목 1 단위 1 chunk_id 99999999 없음");
                });
    }
}
