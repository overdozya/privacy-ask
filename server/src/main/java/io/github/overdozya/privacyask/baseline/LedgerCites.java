package io.github.overdozya.privacyask.baseline;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/** A3 원장 행 인용 LLM 출력(prompts/A3_ledger.schema.json). match: 명시·포괄·없음. */
public record LedgerCites(List<Cite> cites) {

    public record Cite(int flow, String fact, String match, List<RowCite> rows, String reason) {
        public Cite {
            rows = rows == null ? List.of() : List.copyOf(rows);
        }
    }

    public record RowCite(@JsonProperty("row_id") String rowId, String quote) {}

    public LedgerCites {
        cites = cites == null ? List.of() : List.copyOf(cites);
    }
}
