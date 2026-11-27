package io.github.overdozya.privacyask.watch;

import java.util.List;

/** A3 누락 감시 LLM 출력(prompts/A3_watch.schema.json). kind MISSED(목록 안 쟁점을 규칙이 놓침) · OUTSIDE(목록 밖). */
public record WatchFlags(List<Flag> flags) {
    public static final String MISSED = "MISSED", OUTSIDE = "OUTSIDE";

    public record Flag(String kind, String issue, Integer flow, String quote, String reason) {}

    public WatchFlags {
        flags = flags == null ? List.of() : List.copyOf(flags);
    }

    public boolean missedIssue() {
        return flags.stream().anyMatch(f -> MISSED.equals(f.kind()));
    }

    public boolean outsideCatalog() {
        return flags.stream().anyMatch(f -> OUTSIDE.equals(f.kind()));
    }
}
