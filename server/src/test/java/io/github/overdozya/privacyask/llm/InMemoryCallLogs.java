package io.github.overdozya.privacyask.llm;

import java.util.ArrayList;
import java.util.List;

class InMemoryCallLogs implements LlmCallLogWriter {
    final List<CallLogEntry> rows = new ArrayList<>();

    @Override
    public synchronized long write(CallLogEntry entry) {
        rows.add(entry);
        return rows.size();
    }

    List<CallLogEntry.Outcome> outcomes() {
        return rows.stream().map(CallLogEntry::outcome).toList();
    }
}
