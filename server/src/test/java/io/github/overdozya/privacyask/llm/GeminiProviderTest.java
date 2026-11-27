package io.github.overdozya.privacyask.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Gemini 요청 본문·머리, 응답 읽기, 오류 분류, 키 가리기를 로컬 HTTP 서버로 확인한다(06 4.2). */
class GeminiProviderTest {
    private static final String KEY = "test-key-not-real-0123";
    private final JsonMapper json = JsonMapper.builder().build();
    private HttpServer server;
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private final AtomicReference<String> lastKey = new AtomicReference<>();
    private final AtomicReference<String> lastPath = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String reply = "";
    private volatile String retryAfter;
    private volatile long delayMs;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            lastBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            lastKey.set(ex.getRequestHeaders().getFirst("x-goog-api-key"));
            lastPath.set(ex.getRequestURI().getPath());
            if (delayMs > 0) {
                try { Thread.sleep(delayMs); } catch (InterruptedException ignored) { }
            }
            if (retryAfter != null) {
                ex.getResponseHeaders().add("Retry-After", retryAfter);
            }
            byte[] b = reply.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, b.length);
            try (OutputStream o = ex.getResponseBody()) { o.write(b); }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private GeminiProvider provider(boolean sendSchema, boolean sendTemp, String key) {
        LlmProperties p = new LlmProperties("gemini", "gemini-test", BigDecimal.ZERO, key, Duration.ofSeconds(20),
                Duration.ofSeconds(1), false,
                new LlmProperties.Gemini("http://127.0.0.1:" + server.getAddress().getPort() + "/v1beta", sendSchema, sendTemp));
        return new GeminiProvider(p, GeminiProvider.defaultClient(), json);
    }

    private ProviderRequest req(Duration timeout) {
        JsonNode schema = json.readTree("{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"string\"}}}");
        return new ProviderRequest("T", "시스템 글", "{\"x\":1}", schema, new BigDecimal("0"), 512, "default", timeout);
    }

    @Test
    void sendsRequestShapeAndParsesResponse() {
        reply = """
                {"candidates":[{"content":{"role":"model","parts":[{"text":"생각","thought":true},{"text":"{\\"a\\":"},{"text":"\\"b\\"}"}]},
                  "finishReason":"STOP"}],
                 "usageMetadata":{"promptTokenCount":120,"candidatesTokenCount":7,"thoughtsTokenCount":300,"totalTokenCount":427},
                 "modelVersion":"gemini-test-001","responseId":"r1"}
                """;
        ProviderResponse r = provider(true, true, KEY).generate(req(Duration.ofSeconds(5)));
        assertThat(r.text()).isEqualTo("{\"a\":\"b\"}");
        assertThat(r.finishReason()).isEqualTo("STOP");
        assertThat(r.promptTokens()).isEqualTo(120);
        assertThat(r.outputTokens()).isEqualTo(7);
        assertThat(r.thoughtTokens()).isEqualTo(300);
        assertThat(r.modelVersion()).isEqualTo("gemini-test-001");
        assertThat(r.httpStatus()).isEqualTo(200);

        assertThat(lastPath.get()).isEqualTo("/v1beta/models/gemini-test:generateContent");
        assertThat(lastKey.get()).isEqualTo(KEY);
        JsonNode body = json.readTree(lastBody.get());
        assertThat(body.path("systemInstruction").path("parts").get(0).path("text").asString()).isEqualTo("시스템 글");
        assertThat(body.path("contents").get(0).path("role").asString()).isEqualTo("user");
        assertThat(body.path("contents").get(0).path("parts").get(0).path("text").asString()).isEqualTo("{\"x\":1}");
        JsonNode gen = body.path("generationConfig");
        assertThat(gen.path("temperature").decimalValue()).isEqualByComparingTo("0");
        assertThat(gen.path("responseMimeType").asString()).isEqualTo("application/json");
        assertThat(gen.path("responseJsonSchema").path("type").asString()).isEqualTo("object");
        assertThat(gen.path("maxOutputTokens").asInt()).isEqualTo(512);
        assertThat(gen.has("thinkingConfig")).isFalse();
        assertThat(lastBody.get()).doesNotContain(KEY);
    }

    @Test
    void schemaAndTemperatureCanBeTurnedOff() {
        reply = "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"{}\"}]},\"finishReason\":\"STOP\"}]}";
        GeminiProvider p = provider(false, false, KEY);
        p.generate(req(Duration.ofSeconds(5)));
        JsonNode gen = json.readTree(lastBody.get()).path("generationConfig");
        assertThat(gen.has("responseJsonSchema")).isFalse();
        assertThat(gen.has("temperature")).isFalse();
        assertThat(p.sendsTemperature()).isFalse();
    }

    @Test
    void blockedPromptHasNoCandidate() {
        reply = "{\"promptFeedback\":{\"blockReason\":\"SAFETY\"}}";
        ProviderResponse r = provider(true, true, KEY).generate(req(Duration.ofSeconds(5)));
        assertThat(r.finishReason()).isEqualTo("BLOCKED_SAFETY");
        assertThat(r.text()).isEmpty();
    }

    @Test
    void errorsAreClassifiedAndKeyIsMasked() {
        status = 503;
        retryAfter = "4";
        reply = "{\"error\":{\"message\":\"overloaded for " + KEY + "\"}}";
        assertThatThrownBy(() -> provider(true, true, KEY).generate(req(Duration.ofSeconds(5))))
                .isInstanceOfSatisfying(ProviderException.class, e -> {
                    assertThat(e.httpStatus()).isEqualTo(503);
                    assertThat(e.retryable()).isTrue();
                    assertThat(e.retryAfter()).isEqualTo(Duration.ofSeconds(4));
                    assertThat(e.body()).contains("***").doesNotContain(KEY);
                });
        status = 400;
        retryAfter = null;
        assertThatThrownBy(() -> provider(true, true, KEY).generate(req(Duration.ofSeconds(5))))
                .isInstanceOfSatisfying(ProviderException.class, e -> assertThat(e.retryable()).isFalse());
    }

    @Test
    void timeoutIsRetryable() {
        delayMs = 1500;
        reply = "{}";
        assertThatThrownBy(() -> provider(true, true, KEY).generate(req(Duration.ofMillis(300))))
                .isInstanceOfSatisfying(ProviderException.class, e -> {
                    assertThat(e.kind()).isEqualTo(ProviderException.Kind.TIMEOUT);
                    assertThat(e.retryable()).isTrue();
                });
    }

    @Test
    void emptyKeyIsConfigErrorWithoutCall() {
        assertThatThrownBy(() -> provider(true, true, "").generate(req(Duration.ofSeconds(5))))
                .isInstanceOfSatisfying(ProviderException.class, e -> {
                    assertThat(e.kind()).isEqualTo(ProviderException.Kind.CONFIG);
                    assertThat(e.retryable()).isFalse();
                });
        assertThat(lastBody.get()).isNull();
    }

    @Test
    void propertiesToStringHidesKey() {
        LlmProperties p = new LlmProperties("gemini", "m", BigDecimal.ZERO, KEY, Duration.ofSeconds(1),
                Duration.ofSeconds(1), false, new LlmProperties.Gemini("u", true, true));
        assertThat(p.toString()).doesNotContain(KEY).contains("(set)");
    }
}
