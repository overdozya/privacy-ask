package io.github.overdozya.privacyask.llm;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

class PromptCatalogTest {
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void loadsShippedPromptsWithVersionTag() {
        PromptCatalog c = PromptCatalog.load("classpath:prompts", json);
        Prompt ping = c.get("S0_ping");
        assertThat(ping.versionTag()).matches(PromptCatalog.VERSION_TAG).startsWith("1@").hasSizeLessThanOrEqualTo(20);
        assertThat(ping.maxOutputTokens()).isEqualTo(2048);
        assertThat(ping.thinking()).isEqualTo("default");
        // 공통 + 개별, 머리 주석은 보내지 않는다
        assertThat(ping.system()).contains("법무 검토를 대신하지 않는다").contains("nonce").doesNotContain("<!--");
        // 공급자에 보내는 스키마에는 $schema가 없다
        assertThat(ping.schema().has("$schema")).isFalse();
        assertThat(c.has("S0_ping", ping.versionTag())).isTrue();
        assertThat(c.has("S0_ping", "1@00000000")).isFalse();
    }

    @Test
    void hashIsStableAcrossLoads() {
        String a = PromptCatalog.load("classpath:prompts-test", json).get("T_echo").versionTag();
        String b = PromptCatalog.load("classpath:prompts-test", json).get("T_echo").versionTag();
        assertThat(a).isEqualTo(b).startsWith("3@");
    }

    @Test
    void validatorRejectsNonJsonAndSchemaViolations() {
        Prompt p = PromptCatalog.load("classpath:prompts-test", json).get("T_echo");
        OutputValidator v = new OutputValidator(json);
        assertThat(v.check(p, "{\"echo\":\"a\"}").ok()).isTrue();
        assertThat(v.check(p, "not json").error()).startsWith("JSON이 아님");
        assertThat(v.check(p, "[1]").error()).contains("객체");
        assertThat(v.check(p, "{\"echo\":\"z\"}").error()).startsWith("스키마 위반");
        assertThat(v.check(p, "{\"echo\":\"a\",\"extra\":1}").error()).startsWith("스키마 위반");
        assertThat(v.check(p, "{}").error()).startsWith("스키마 위반");
        assertThat(v.check(p, " ").error()).isEqualTo("빈 출력");
    }
}
