package io.github.overdozya.privacyask.knowledge;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

import tools.jackson.databind.JsonNode;

/**
 * 쟁점 꾸러미(issue_packages · package_items · package_item_units), 판마다. 지금 판은 is_current = 1인 판 하나(06 3.1).
 * trigger·조건·해석 쟁점은 JSON 그대로 들고, 조건식 해석은 pa/rules(S5)가 한다. JsonNode는 고치지 않는다.
 */
public final class Packages {

    public record Unit(int ord, String unitKey, Integer chunkId, Integer annexId) {}

    public record Item(int itemId, String version, String packageId, int ord, String targetKind, String targetKey,
                       int targetDocId, String role, String reason, String source, List<String> conds,
                       boolean answerBasis, String basisNote, int unitCount, int charCount, List<Unit> units) {}

    /**
     * conditionIds = conditions_json 항목 id. interpIssueIds = 상태가 '해석 쟁점'인 interp id(C15).
     */
    public record Package(String version, String packageId, boolean current, String issueName, String origin,
                          JsonNode trigger, String triggerStatus, List<String> openedBy, String triggerNote,
                          List<String> ruleIds, JsonNode conditions, JsonNode excluded, int itemCount, int unitCount,
                          int totalChars, String status, String contentSha256, Set<String> conditionIds,
                          Set<String> interpIssueIds, List<Item> items) {}

    private final Map<String, Map<String, Package>> byVersion; // version → package_id → 꾸러미(package_id 순)
    private final String currentVersion;

    Packages(List<Package> all, String currentVersion) {
        Map<String, Map<String, Package>> m = new TreeMap<>();
        for (Package p : all) {
            m.computeIfAbsent(p.version(), k -> new TreeMap<>()).put(p.packageId(), p);
        }
        Map<String, Map<String, Package>> frozen = new LinkedHashMap<>();
        m.forEach((k, v) -> frozen.put(k, java.util.Collections.unmodifiableMap(new LinkedHashMap<>(v))));
        this.byVersion = java.util.Collections.unmodifiableMap(frozen);
        this.currentVersion = currentVersion;
    }

    /** 지금 판(예: v2). 사안 시작 때 이 값을 cases.package_version에 고정한다. */
    public String currentVersion() {
        return currentVersion;
    }

    public Set<String> versions() {
        return byVersion.keySet();
    }

    public boolean hasVersion(String version) {
        return byVersion.containsKey(version);
    }

    /** 지금 판의 꾸러미 전부(package_id 순). */
    public Collection<Package> current() {
        return inVersion(currentVersion);
    }

    public Collection<Package> inVersion(String version) {
        return byVersion.getOrDefault(version, Map.of()).values();
    }

    public Optional<Package> get(String version, String packageId) {
        return Optional.ofNullable(byVersion.getOrDefault(version, Map.of()).get(packageId));
    }

    public Optional<Package> current(String packageId) {
        return get(currentVersion, packageId);
    }

    public Optional<Package> byIssue(String version, String issueName) {
        return inVersion(version).stream().filter(p -> p.issueName().equals(issueName)).findFirst();
    }

    public int packageCount() {
        return byVersion.values().stream().mapToInt(Map::size).sum();
    }

    static List<String> strings(JsonNode arr) {
        List<String> out = new ArrayList<>();
        if (arr != null && arr.isArray()) {
            for (JsonNode x : arr) {
                out.add(x.asString());
            }
        }
        return List.copyOf(out);
    }
}
