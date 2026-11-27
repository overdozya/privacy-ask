package io.github.overdozya.privacyask.llm;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Gemini generateContent(06 4.2). REST 하나, 키는 머리 x-goog-api-key로만 보내고 어디에도 남기지 않는다.
 * JDK HttpClient(HTTP/2, 연결 재사용)를 직접 쓴다: 시도마다 마감이 달라 요청 단위 시간 제한이 필요하다.
 */
public class GeminiProvider implements LlmProvider {
    private static final int BODY_LIMIT = 2000;

    private final LlmProperties props;
    private final HttpClient http;
    private final JsonMapper json;

    public GeminiProvider(LlmProperties props, HttpClient http, JsonMapper json) {
        this.props = props;
        this.http = http;
        this.json = json;
    }

    public static HttpClient defaultClient() {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    @Override
    public String name() {
        return "gemini";
    }

    @Override
    public boolean sendsTemperature() {
        return props.gemini().sendTemperature();
    }

    @Override
    public ProviderResponse generate(ProviderRequest req) {
        if (!props.hasApiKey()) {
            throw ProviderException.config("LLM_API_KEY가 비어 있다");
        }
        String body = json.writeValueAsString(requestBody(req));
        URI uri = URI.create(props.gemini().baseUrl() + "/models/"
                + URLEncoder.encode(props.model(), StandardCharsets.UTF_8) + ":generateContent");
        HttpRequest httpReq = HttpRequest.newBuilder(uri)
                .timeout(req.timeout())
                .header("Content-Type", "application/json; charset=utf-8")
                .header("x-goog-api-key", props.apiKey())
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        long t0 = System.nanoTime();
        HttpResponse<String> res;
        try {
            res = http.send(httpReq, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (HttpTimeoutException e) {
            throw ProviderException.timeout(ms(t0));
        } catch (IOException e) {
            throw ProviderException.connect(ms(t0), mask(String.valueOf(e.getMessage())));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw ProviderException.connect(ms(t0), "중단됨");
        }
        long latency = ms(t0);
        int status = res.statusCode();
        if (status != 200) {
            Duration retryAfter = res.headers().firstValue("Retry-After").flatMap(GeminiProvider::parseSeconds).orElse(null);
            throw ProviderException.http(status, retryAfter, latency, truncate(mask(res.body())));
        }
        return parse(res.body(), status, latency);
    }

    ObjectNode requestBody(ProviderRequest req) {
        ObjectNode root = json.createObjectNode();
        root.putObject("systemInstruction").putArray("parts").addObject().put("text", req.system());
        ObjectNode user = root.putArray("contents").addObject();
        user.put("role", "user");
        user.putArray("parts").addObject().put("text", req.user());
        ObjectNode gen = root.putObject("generationConfig");
        if (props.gemini().sendTemperature() && req.temperature() != null) {
            gen.put("temperature", req.temperature());
        }
        gen.put("responseMimeType", "application/json");
        if (props.gemini().sendSchema() && req.schema() != null) {
            gen.set("responseJsonSchema", req.schema());
        }
        if (req.maxOutputTokens() > 0) {
            gen.put("maxOutputTokens", req.maxOutputTokens());
        }
        if (req.thinking() != null && !"default".equals(req.thinking())) {
            gen.putObject("thinkingConfig").put("thinkingLevel", req.thinking());
        }
        return root;
    }

    ProviderResponse parse(String body, int status, long latency) {
        JsonNode root = json.readTree(body);
        JsonNode cand = root.path("candidates").path(0);
        String finish;
        StringBuilder text = new StringBuilder();
        if (cand.isMissingNode()) {
            String block = root.path("promptFeedback").path("blockReason").asString("");
            finish = block.isEmpty() ? "NO_CANDIDATE" : "BLOCKED_" + block;
        } else {
            finish = cand.path("finishReason").asString("UNKNOWN");
            JsonNode parts = cand.path("content").path("parts");
            if (parts instanceof ArrayNode arr) {
                for (JsonNode p : arr) {
                    if (p.path("thought").asBoolean(false)) {
                        continue;
                    }
                    text.append(p.path("text").asString(""));
                }
            }
        }
        JsonNode usage = root.path("usageMetadata");
        return new ProviderResponse(text.toString(), finish,
                intOrNull(usage, "promptTokenCount"),
                intOrNull(usage, "candidatesTokenCount"),
                intOrNull(usage, "thoughtsTokenCount"),
                status, latency,
                root.path("modelVersion").asString(null));
    }

    private static Integer intOrNull(JsonNode n, String field) {
        JsonNode v = n.path(field);
        return v.isNumber() ? v.asInt() : null;
    }

    private static java.util.Optional<Duration> parseSeconds(String v) {
        try {
            return java.util.Optional.of(Duration.ofSeconds(Long.parseLong(v.trim())));
        } catch (NumberFormatException e) {
            return java.util.Optional.empty();
        }
    }

    private String mask(String s) {
        if (s == null) {
            return null;
        }
        return props.hasApiKey() ? s.replace(props.apiKey(), "***") : s;
    }

    private static String truncate(String s) {
        return s == null || s.length() <= BODY_LIMIT ? s : s.substring(0, BODY_LIMIT) + "…";
    }

    private static long ms(long t0) {
        return (System.nanoTime() - t0) / 1_000_000;
    }
}
