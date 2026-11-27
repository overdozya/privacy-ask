package io.github.overdozya.privacyask.tools;

import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

import io.github.overdozya.privacyask.knowledge.Knowledge;

@Configuration(proxyBeanMethods = false)
public class ToolsConfig {
    private static final Logger log = LoggerFactory.getLogger(ToolsConfig.class);

    /** 질의 모델: 파일이 없으면 NONE 꼴(글자 검색만). 실제로 띄우는 것은 처음 쓸 때 또는 warmup. */
    @Bean
    QueryEmbedder queryEmbedder(ToolsProperties props, @Value("${app.data-dir:../data}") String dataDir) {
        Path dir = props.modelDir() == null || props.modelDir().isBlank()
                ? Path.of(dataDir, "models", "kure-v3-onnx") : Path.of(props.modelDir());
        return OnnxQueryEmbedder.of(dir);
    }

    /** 기동 때 한 번: 안내서 청크 + 벡터 + 글자 색인(지식 적재 전이면 빈 색인). */
    @Bean
    GuideIndex guideIndex(Knowledge knowledge, JdbcClient jdbc) {
        if (!knowledge.isLoaded()) {
            return GuideIndex.of(java.util.List.of(), null);
        }
        GuideIndex g = GuideIndex.load(knowledge.snapshot(), jdbc);
        GuideIndex.Stats s = g.stats();
        log.info("A6 안내서 색인 {} ms: 청크 {}, 벡터 {}(없음 {}), 2-그램 {}, 힙 +{} MB", s.loadMs(), s.chunks(), s.vectors(),
                s.missingVectors(), s.textTerms(), Math.round((s.heapAfterBytes() - s.heapBeforeBytes()) / 1048576.0));
        return g;
    }

    @Bean
    GuideTools guideTools(Knowledge knowledge, GuideIndex index, QueryEmbedder embedder, ToolsProperties props) {
        return new GuideTools(knowledge, index, embedder, props);
    }

    /** 벡터가 있고 모델 파일이 있으면 기동 뒤 따로 스레드에서 모델을 띄운다(첫 검색이 띄우는 시간을 안 지게). */
    @Bean
    ApplicationListener<ApplicationReadyEvent> guideToolsWarmup(GuideTools tools) {
        return e -> {
            if (tools.props().warmup() && tools.enabled() && tools.index().vectors() > 0
                    && tools.embedder() instanceof OnnxQueryEmbedder m && m.available()) {
                Thread.ofVirtual().name("a6-warmup").start(() -> {
                    try {
                        m.load();
                    } catch (RuntimeException ex) {
                        log.warn("A6 질의 모델 warmup 실패: {}", ex.getMessage());
                    }
                });
            }
        };
    }
}
