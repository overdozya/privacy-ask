package io.github.overdozya.privacyask.knowledge;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import tools.jackson.databind.JsonNode;

/**
 * 처리 원장(SKT 처리방침 첨부 1~10). 행 해시(인용 C13) = sha256(fields_json 글 그대로, 06 2.2).
 * 서비스명은 PDF 줄바꿈이 낱말 가운데 공백을 넣으므로 공백을 모두 뺀 service_key로 맞춘다(2b단계 결과).
 */
public final class Ledger {
    private static final Pattern SPACE = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);

    public record Version(String versionId, String attachment, LocalDate publishedOn, String title, String fileName,
                          String fileSha256, int pageCount) {}

    public record Field(String name, String text) {}

    public record Segment(String segmentId, String colName, String colKind, String segType, int seq, String parentId,
                          String term, String sourceId, String text) {}

    public record Row(String rowId, String versionId, String attachment, int seq, int tableNo, String tableTitle,
                      String service, String serviceKey, List<String> serviceKeys, String fieldsJson,
                      List<Field> fields, String textHash, JsonNode colKinds, JsonNode pages, int firstPage,
                      JsonNode merged, JsonNode flags, List<Segment> segments) {
        /** 칸 글을 줄바꿈으로 이은 것(store.py ledger_text, 근거 문구 C03). */
        public String text() {
            StringBuilder sb = new StringBuilder();
            for (Field f : fields) {
                if (!sb.isEmpty()) {
                    sb.append('\n');
                }
                sb.append(f.text());
            }
            return sb.toString();
        }

        public Optional<String> field(String name) {
            return fields.stream().filter(f -> f.name().equals(name)).map(Field::text).findFirst();
        }
    }

    public record Unextracted(String attachment, int page, String kind, String ref, String text) {}

    public enum MatchKind { EXACT, PARTIAL, NONE }

    /**
     * 서비스명 → 원장 후보. EXACT: 행의 쉼표로 나눈 서비스 중 하나가 공백 뺀 이름과 같음.
     * EXACT가 없을 때만 PARTIAL: 한쪽이 다른 쪽을 품음. 행은 row_id 순.
     */
    public record ServiceMatch(String query, String key, MatchKind kind, List<Row> rows) {}

    private final Map<String, Row> rows;          // row_id 순
    private final List<Version> versions;
    private final Set<String> versionIds;
    private final Set<String> attachments;
    private final List<Unextracted> unextracted;
    private final int segmentCount;

    Ledger(List<Version> versions, List<Row> rows, List<Unextracted> unextracted) {
        Map<String, Row> m = new LinkedHashMap<>();
        rows.stream().sorted(java.util.Comparator.comparing(Row::rowId)).forEach(r -> m.put(r.rowId(), r));
        this.rows = java.util.Collections.unmodifiableMap(m);
        this.versions = List.copyOf(versions);
        Set<String> ids = new LinkedHashSet<>();
        Set<String> att = new LinkedHashSet<>();
        for (Version v : versions) {
            ids.add(v.versionId());
            att.add(v.attachment());
        }
        this.versionIds = java.util.Collections.unmodifiableSet(ids);
        this.attachments = java.util.Collections.unmodifiableSet(att);
        this.unextracted = List.copyOf(unextracted);
        this.segmentCount = rows.stream().mapToInt(r -> r.segments().size()).sum();
    }

    public static String serviceKey(String name) {
        return name == null ? "" : SPACE.matcher(name).replaceAll("");
    }

    public static List<String> splitServiceKey(String serviceKey) {
        List<String> out = new ArrayList<>();
        for (String s : serviceKey.split(",")) {
            if (!s.isEmpty()) {
                out.add(s);
            }
        }
        return List.copyOf(out);
    }

    public Optional<Row> row(String rowId) {
        return Optional.ofNullable(rows.get(rowId));
    }

    public Collection<Row> rows() {
        return rows.values();
    }

    public List<Row> rowsIn(String attachment) {
        return rows.values().stream().filter(r -> r.attachment().equals(attachment)).toList();
    }

    public List<Version> versions() {
        return versions;
    }

    public boolean hasVersion(String versionId) {
        return versionIds.contains(versionId);
    }

    /** 가장 늦은 판(공고일 기준). 사안 시작 때 cases.ledger_version 기본값. */
    public Optional<String> latestVersion() {
        return versions.stream().max(java.util.Comparator.comparing(Version::publishedOn)).map(Version::versionId);
    }

    public Set<String> attachments() {
        return attachments;
    }

    /** 원장에 나오는 서비스 이름(공백 뺀 것, 쉼표로 나눈 것), 가나다 순. F-S1 보기·서비스명 맞추기용. */
    public List<String> serviceKeys() {
        return rows.values().stream().flatMap(r -> r.serviceKeys().stream()).distinct().sorted().toList();
    }

    public ServiceMatch forService(String serviceName) {
        return forService(serviceName, null);
    }

    /** attachments가 null이면 모든 첨부, 아니면 그 첨부의 행만(예: L1 = 첨부1·2). */
    public ServiceMatch forService(String serviceName, Collection<String> onlyAttachments) {
        String key = serviceKey(serviceName);
        if (key.isEmpty()) {
            return new ServiceMatch(serviceName, key, MatchKind.NONE, List.of());
        }
        List<Row> exact = new ArrayList<>(), partial = new ArrayList<>();
        for (Row r : rows.values()) {
            if (onlyAttachments != null && !onlyAttachments.contains(r.attachment())) {
                continue;
            }
            if (r.serviceKeys().contains(key)) {
                exact.add(r);
            } else if (r.serviceKeys().stream().anyMatch(s -> s.contains(key) || key.contains(s))) {
                partial.add(r);
            }
        }
        if (!exact.isEmpty()) {
            return new ServiceMatch(serviceName, key, MatchKind.EXACT, List.copyOf(exact));
        }
        if (!partial.isEmpty()) {
            return new ServiceMatch(serviceName, key, MatchKind.PARTIAL, List.copyOf(partial));
        }
        return new ServiceMatch(serviceName, key, MatchKind.NONE, List.of());
    }

    public List<Unextracted> unextracted() {
        return unextracted;
    }

    public int size() {
        return rows.size();
    }

    public int segmentCount() {
        return segmentCount;
    }

    static Map<String, List<Segment>> groupSegments(List<Map.Entry<String, Segment>> segs) {
        Map<String, List<Segment>> m = new HashMap<>();
        for (var e : segs) {
            m.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(e.getValue());
        }
        return m;
    }
}
