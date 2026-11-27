package io.github.overdozya.privacyask.record;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 사안 상태 한 번 읽기(CaseReader.load, 06 3.4·6.3). 사실은 모든 판을 들고, 지금 판은 여기서 고른다.
 * 단계는 요약만(input_json·output_json은 CaseReader.trace).
 */
public record CaseState(CaseRow caseRow, List<Flow> flows, List<Turn> turns, List<Fact> facts, List<StepSummary> steps) {

    public record CaseRow(String caseId, String requesterId, String content, String serviceName, String intakeType,
                          String outOfScopeKind, String state, String ruleSetVersion, String packageVersion,
                          String ledgerVersion, LocalDate referenceDate, String referenceDateBasis,
                          LocalDateTime createdAt, LocalDateTime updatedAt, LocalDateTime closedAt) {}

    public record Flow(int flowNo, String label, String origin, String destination, String serviceName,
                       int createdStep) {}

    public record Turn(int turnNo, String role, String kind, Integer flowNo, String factId, Integer askNo,
                       Integer replyTo, String text, List<String> choices, int stepSeq, LocalDateTime at) {
        public boolean isQuestion() {
            return "QUESTION".equals(kind);
        }
    }

    public record Fact(long factRowId, Integer flowNo, String factId, int rev, String status, String sourceKind,
                       Integer sourceTurn, int sourceStep, String basisQuote, String ledgerVersion, String note,
                       LocalDateTime recordedAt, List<CaseInput.Value> values, List<String> ledgerRows) {
        public List<String> labels() {
            return values.stream().map(CaseInput.Value::label).toList();
        }
    }

    public record StepSummary(int seq, String step, Integer flowNo, String actor, String name, String promptId,
                              String promptVersion, String modelName, BigDecimal temperature, String status,
                              int attempts, LocalDateTime at) {
        public boolean ok() {
            return "OK".equals(status) || "RETRY_OK".equals(status);
        }
    }

    /**
     * 이번에 처리하는 사용자 입력(늦은 저장 거절, 06 3.4). turnNo = 마지막 USER 턴(첫 문의만이면 0),
     * boundarySeq = 그 입력 앞의 마지막 단계(답 턴의 step_seq = 그 답이 받은 질문을 만든 A2 단계, 첫 문의면 0).
     * 이 입력을 처리한 단계는 seq > boundarySeq.
     */
    public record InputMark(int turnNo, int boundarySeq) {
        public static final InputMark INQUIRY = new InputMark(0, 0);
    }

    public InputMark inputMark() {
        return lastUserTurn().map(t -> new InputMark(t.turnNo(), t.stepSeq())).orElse(InputMark.INQUIRY);
    }

    public Optional<Turn> lastUserTurn() {
        return turns.stream().filter(t -> "USER".equals(t.role())).max(Comparator.comparingInt(Turn::turnNo));
    }

    public Optional<Turn> lastTurn() {
        return turns.stream().max(Comparator.comparingInt(Turn::turnNo));
    }

    public Optional<Turn> turn(int turnNo) {
        return turns.stream().filter(t -> t.turnNo() == turnNo).findFirst();
    }

    /** 지금 판: (흐름, 사실 ID)마다 rev가 가장 큰 행. 공통 사실은 flowNo null. 흐름·사실 순. */
    public List<Fact> currentFacts() {
        Map<String, Fact> m = new LinkedHashMap<>();
        for (Fact f : facts) {
            String k = (f.flowNo() == null ? 0 : f.flowNo()) + "\u0000" + f.factId();
            Fact old = m.get(k);
            if (old == null || f.rev() > old.rev()) {
                m.put(k, f);
            }
        }
        List<Fact> out = new ArrayList<>(m.values());
        out.sort(Comparator.comparing((Fact f) -> f.flowNo() == null ? 0 : f.flowNo()).thenComparing(Fact::factId));
        return out;
    }

    /** 흐름 사실이면 그 흐름의 지금 판, 없으면 공통 사실의 지금 판(store.py resolve_fact와 같은 뜻). */
    public Optional<Fact> current(Integer flowNo, String factId) {
        Optional<Fact> own = currentFacts().stream()
                .filter(f -> f.factId().equals(factId) && Objects.equals(f.flowNo(), flowNo)).findFirst();
        if (own.isPresent() || flowNo == null) {
            return own;
        }
        return current(null, factId);
    }

    public Optional<Flow> flow(int flowNo) {
        return flows.stream().filter(f -> f.flowNo() == flowNo).findFirst();
    }

    public Optional<StepSummary> step(int seq) {
        return steps.stream().filter(s -> s.seq() == seq).findFirst();
    }

    public int lastSeq() {
        return steps.stream().mapToInt(StepSummary::seq).max().orElse(0);
    }

    /** 이번 입력(inputMark) 뒤에 같은 이름의 단계가 OK로 끝났나(NextAction 판단용). */
    public boolean doneSinceInput(String step, String name, Integer flowNo) {
        int b = inputMark().boundarySeq();
        return steps.stream().anyMatch(s -> s.seq() > b && s.step().equals(step) && s.name().equals(name)
                && Objects.equals(s.flowNo(), flowNo) && s.ok());
    }
}
