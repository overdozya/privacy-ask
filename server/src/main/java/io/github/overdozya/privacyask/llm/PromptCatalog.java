package io.github.overdozya.privacyask.llm;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 기동 때 prompts/를 한 번 읽는다(06 4.5·6.2). 파일 쌍: ID.md + ID.schema.json, 공통은 _common.md.
 * 머리 첫 줄: <!-- version: 1; max_output_tokens: 512; thinking: default -->
 * 스키마 파일은 검증용(엄격), 공급자에게는 sendable()로 줄인 판을 보낸다. 해시는 공통 + 개별 + 스키마 파일 글 전체(머리 포함). 버전만 보면 사람이 고친 판, 해시까지 보면 실제 보낸 글·생성 설정.
 */
public final class PromptCatalog {
    public static final Pattern HEADER = Pattern.compile(
            "\\A<!--\\s*version:\\s*([^;\\s@]+)\\s*;\\s*max_output_tokens:\\s*(\\d+)\\s*;\\s*thinking:\\s*([a-z]+)\\s*-->[ \\t]*\\R");
    public static final Pattern VERSION_TAG = Pattern.compile("^[^@\\s]+@[0-9a-f]{8}$");
    private static final Pattern COMMENT_HEADER = Pattern.compile("\\A<!--.*?-->[ \\t]*\\R", Pattern.DOTALL);

    private final Map<String, Prompt> prompts;

    private PromptCatalog(Map<String, Prompt> prompts) {
        this.prompts = Map.copyOf(prompts);
    }

    /** location 예: "classpath:prompts" */
    public static PromptCatalog load(String location, JsonMapper json) {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        try {
            Resource common = resolver.getResource(location + "/_common.md");
            if (!common.exists()) {
                throw new IllegalStateException("공통 프롬프트가 없다: " + location + "/_common.md");
            }
            String commonRaw = read(common);
            String commonBody = COMMENT_HEADER.matcher(commonRaw).replaceFirst("").strip();
            SchemaRegistry registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
            Map<String, Prompt> out = new TreeMap<>();
            for (Resource md : resolver.getResources(location + "/*.md")) {
                String file = md.getFilename();
                if (file == null || file.startsWith("_")) {
                    continue;
                }
                String id = file.substring(0, file.length() - 3);
                Resource schemaRes = resolver.getResource(location + "/" + id + ".schema.json");
                if (!schemaRes.exists()) {
                    throw new IllegalStateException("스키마 파일이 없다: " + id + ".schema.json");
                }
                String raw = read(md);
                Matcher h = HEADER.matcher(raw);
                if (!h.find()) {
                    throw new IllegalStateException("프롬프트 머리가 형식과 다르다: " + file);
                }
                String body = raw.substring(h.end()).strip();
                String schemaRaw = read(schemaRes);
                // 파일 = 검증할 스키마(엄격: 길이·개수·범위 포함). 보낼 스키마 = 그것에서 방언 표시와 SEND_DROP을 뺀 단순판
                JsonNode strictNode = json.readTree(schemaRaw);
                JsonNode schemaNode = sendable(strictNode);
                Schema compiled = registry.getSchema(schemaRaw, InputFormat.JSON);
                String sha8 = sha256(commonRaw + "\u0000" + raw + "\u0000" + schemaRaw).substring(0, 8);
                String version = h.group(1);
                String tag = version + "@" + sha8;
                if (tag.length() > 20) {
                    throw new IllegalStateException("버전 표기가 20자를 넘는다(steps.prompt_version): " + tag);
                }
                out.put(id, new Prompt(id, version, tag, commonBody + "\n\n" + body, schemaNode, strictNode, compiled,
                        Integer.parseInt(h.group(2)), h.group(3)));
            }
            return new PromptCatalog(out);
        } catch (IOException e) {
            throw new IllegalStateException("프롬프트를 읽지 못했다: " + location, e);
        }
    }

    /**
     * 공급자에게 보내지 않는 스키마 낱말. Gemini는 길이·개수·숫자 범위가 많은 스키마를 "too many states for serving"(400)으로
     * 거부한다(S6 A1_facts에서 확인). 이 제약은 우리 쪽 검증(compiled = 파일 그대로)에서만 본다.
     */
    public static final java.util.Set<String> SEND_DROP = java.util.Set.of("$schema", "maxItems", "minItems", "minimum",
            "maximum", "exclusiveMinimum", "exclusiveMaximum", "maxLength", "minLength", "pattern", "format");

    /** 보낼 스키마: 검증 스키마의 깊은 복사에서 SEND_DROP 낱말을 뺀다(properties 아래의 칸 이름은 건드리지 않는다). */
    static JsonNode sendable(JsonNode strict) {
        JsonNode copy = strict.deepCopy();
        strip(copy, false);
        return copy;
    }

    private static void strip(JsonNode node, boolean namesMap) {
        if (node instanceof tools.jackson.databind.node.ObjectNode o) {
            if (!namesMap) {
                SEND_DROP.forEach(o::remove);
            }
            for (String name : new java.util.ArrayList<>(o.propertyNames())) {
                boolean childIsNames = !namesMap && ("properties".equals(name) || "$defs".equals(name)
                        || "patternProperties".equals(name));
                strip(o.get(name), childIsNames);
            }
        } else if (node instanceof tools.jackson.databind.node.ArrayNode a) {
            a.forEach(x -> strip(x, false));
        }
    }

    public Prompt get(String id) {
        Prompt p = prompts.get(id);
        if (p == null) {
            throw new IllegalArgumentException("없는 프롬프트: " + id);
        }
        return p;
    }

    public boolean has(String id, String versionTag) {
        Prompt p = prompts.get(id);
        return p != null && p.versionTag().equals(versionTag);
    }

    public Collection<Prompt> all() {
        return prompts.values();
    }

    private static String read(Resource r) throws IOException {
        try (InputStream in = r.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
