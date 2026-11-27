package io.github.overdozya.privacyask.knowledge;

import java.nio.file.Path;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
public class KnowledgeConfig {

    /** 기동 때 한 번(06 3.1). 검사를 어기면 이 빈을 만들지 못해 기동이 멈춘다. */
    @Bean
    Knowledge knowledge(JdbcClient jdbc, JsonMapper json, @Value("${app.data-dir:../data}") String dataDir) {
        return Knowledge.load(new KnowledgeLoader(jdbc, json), Path.of(dataDir, "out", "mysql", "manifest.json"), json);
    }
}
