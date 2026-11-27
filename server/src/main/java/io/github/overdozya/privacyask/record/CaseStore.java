package io.github.overdozya.privacyask.record;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.overdozya.privacyask.knowledge.Catalog;
import io.github.overdozya.privacyask.knowledge.Knowledge;
import io.github.overdozya.privacyask.knowledge.KnowledgeSnapshot;
import io.github.overdozya.privacyask.knowledge.Ledger;
import io.github.overdozya.privacyask.knowledge.Packages;
import io.github.overdozya.privacyask.record.CaseInput.CitationEntry;
import io.github.overdozya.privacyask.record.CaseInput.IssueEntry;
import io.github.overdozya.privacyask.record.CaseInput.NewCase;
import io.github.overdozya.privacyask.record.CaseInput.NewFact;
import io.github.overdozya.privacyask.record.CaseInput.NewFlow;
import io.github.overdozya.privacyask.record.CaseInput.NewJudgment;
import io.github.overdozya.privacyask.record.CaseInput.NewStep;
import io.github.overdozya.privacyask.record.CaseInput.NewTurn;
import io.github.overdozya.privacyask.record.CaseInput.SlotEntry;
import io.github.overdozya.privacyask.record.CaseInput.Value;
import tools.jackson.databind.json.JsonMapper;

/**
 * 사안 기록(D9) 저장 함수(build/case/store.py를 옮김, 06 2.6). 에이전트는 이 함수로만 쓴다.
 * 거부 규칙 C01~C17을 store.py와 같은 순서·코드로 검사하고, 통과한 것만 쓴다. 지식(꾸러미·원장·조문)은 스냅샷에서 찾는다.
 * DB 칸 제약이 막으면 Rejected("DB"). 메서드 하나 = 트랜잭션 하나(바깥 트랜잭션이 있으면 거기에 합류, CaseLock.write).
 */
public class CaseStore {
    public static final String QUOTE_SEP = " … ";
    public static final String EXPLICIT_MARK = "명시";
    public static final Pattern PROMPT_VERSION = Pattern.compile("^[^@\\s]+@[0-9a-f]{8}$");
    private static final Pattern SPACE = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Set<String> CASE_FIELDS = Set.of("intake_type", "out_of_scope_kind", "state",
            "reference_date", "reference_date_basis", "service_name", "closed_at");
    /** MySQL 칸 제약 오류 번호: NOT NULL 1048·1364, 중복 1062, FK 1216·1217·1451·1452, CHECK 3819, JSON 3140, 길이 1406. */
    private static final Set<Integer> CONSTRAINT_ERRORS = Set.of(1048, 1364, 1062, 1216, 1217, 1451, 1452, 3819, 3140, 1406);

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final Knowledge knowledge;
    private final JsonMapper json;
    private final PromptCheck prompts;

    public CaseStore(JdbcClient jdbc, TransactionTemplate tx, Knowledge knowledge, JsonMapper json, PromptCheck prompts) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.knowledge = knowledge;
        this.json = json;
        this.prompts = prompts;
    }

    private KnowledgeSnapshot k() {
        return knowledge.snapshot();
    }

    /** 검사를 모두 통과한 쓰기만 남긴다. DB 제약이 막으면 Rejected("DB")(두 번째 벽). */
    private <T> T write(Supplier<T> body) {
        try {
            return tx.execute(st -> body.get());
        } catch (DataAccessException e) {
            Throwable c = e.getMostSpecificCause();
            if (c instanceof SQLException se && CONSTRAINT_ERRORS.contains(se.getErrorCode())) {
                throw new Rejected("DB", se.getErrorCode() + " " + se.getMessage(), e);
            }
            throw e;
        }
    }

    private String dumps(Object o) {
        return json.writeValueAsString(o);
    }

    // ───────── 사안·단계·흐름·턴 ─────────

    /** 사안 시작 때 쓰는 판 셋을 고정한다. 기준표·지식에 없는 판이면 거부. */
    public String addCase(NewCase c) {
        if (c.requesterId() == null || c.requesterId().isBlank()) {
            throw new Rejected("C01", "문의자(requester_id) 없음");
        }
        if (c.packageVersion() == null || c.packageVersion().isEmpty()) {
            throw new Rejected("C11", "사안 꾸러미 판 없음");
        }
        if (!k().packages().hasVersion(c.packageVersion())) {
            throw new Rejected("C11", "꾸러미 판 '" + c.packageVersion() + "'가 지식에 없음");
        }
        if (!Objects.equals(c.ruleSetVersion(), k().catalog().ruleSetVersion())) {
            throw new Rejected("C12", "규칙 세트 '" + c.ruleSetVersion() + "'");
        }
        if (c.ledgerVersion() != null && !k().ledger().hasVersion(c.ledgerVersion())) {
            throw new Rejected("C12", "처리방침 판 '" + c.ledgerVersion() + "'가 처리 원장에 없음");
        }
        return write(() -> {
            jdbc.sql("""
                    INSERT INTO cases (case_id, requester_id, content, service_name, state, rule_set_version,
                                       package_version, ledger_version, created_at, updated_at)
                    VALUES (?, ?, ?, ?, 'ASKING', ?, ?, ?, ?, ?)""")
                    .params(c.caseId(), c.requesterId(), c.content(), c.serviceName(), c.ruleSetVersion(),
                            c.packageVersion(), c.ledgerVersion(), c.at(), c.at())
                    .update();
            return c.caseId();
        });
    }

    /** 바꿀 수 있는 칸: intake_type, out_of_scope_kind, state, reference_date, reference_date_basis, service_name, closed_at. */
    public void updateCase(String caseId, java.time.LocalDateTime at, Map<String, Object> fields) {
        if (!CASE_FIELDS.containsAll(fields.keySet())) {
            Set<String> extra = new HashSet<>(fields.keySet());
            extra.removeAll(CASE_FIELDS);
            throw new IllegalArgumentException("바꿀 수 없는 칸: " + extra);
        }
        if (fields.isEmpty()) {
            return;
        }
        List<String> keys = new ArrayList<>(fields.keySet());
        StringBuilder sql = new StringBuilder("UPDATE cases SET ");
        List<Object> args = new ArrayList<>();
        for (String key : keys) {
            sql.append(key).append("=?, ");
            args.add(fields.get(key));
        }
        sql.append("updated_at=? WHERE case_id=?");
        args.add(at);
        args.add(caseId);
        int n = write(() -> jdbc.sql(sql.toString()).params(args).update());
        if (n == 0) {
            throw new CaseNotFound(caseId);
        }
    }

    public int addStep(NewStep s) {
        if ("LLM".equals(s.actor()) && (s.promptId() == null || s.promptVersion() == null || s.modelName() == null
                || s.temperature() == null)) {
            throw new Rejected("C17", s.step() + " " + s.name());
        }
        if (s.promptVersion() != null && !PROMPT_VERSION.matcher(s.promptVersion()).matches()) {
            throw new Rejected("C17", s.step() + " " + s.name() + ": 프롬프트 버전 '" + s.promptVersion()
                    + "'는 '<버전>@<해시 8자>'가 아님");
        }
        if ("LLM".equals(s.actor()) && !prompts.known(s.promptId(), s.promptVersion())) {
            throw new Rejected("C17", s.step() + " " + s.name() + ": 프롬프트 " + s.promptId() + " "
                    + s.promptVersion() + "가 실행 중 PromptCatalog에 없음");
        }
        String in = dumps(s.input()), out = dumps(s.output());
        return write(() -> {
            int seq = jdbc.sql("SELECT COALESCE(MAX(seq),0)+1 FROM steps WHERE case_id=?").param(s.caseId())
                    .query(Integer.class).single();
            jdbc.sql("""
                    INSERT INTO steps (case_id, seq, step, flow_no, actor, name, prompt_id, prompt_version, model_name,
                                       temperature, code_version, input_json, output_json, status, attempts, at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                    .params(s.caseId(), seq, s.step(), s.flowNo(), s.actor(), s.name(), s.promptId(),
                            s.promptVersion(), s.modelName(), s.temperature(), s.codeVersion(), in, out,
                            s.status() == null ? "OK" : s.status(), s.attempts(), s.at())
                    .update();
            return seq;
        });
    }

    public void addFlow(NewFlow f) {
        if (step(f.caseId(), f.createdStep()).isEmpty()) {
            throw new Rejected("C01", "흐름 " + f.flowNo() + ": 단계 " + f.createdStep() + " 없음");
        }
        write(() -> jdbc.sql("""
                INSERT INTO flows (case_id, flow_no, label, origin, destination, service_name, created_step)
                VALUES (?, ?, ?, ?, ?, ?, ?)""")
                .params(f.caseId(), f.flowNo(), f.label(), f.origin(), f.destination(), f.serviceName(),
                        f.createdStep())
                .update());
    }

    public int addTurn(NewTurn t) {
        if (step(t.caseId(), t.stepSeq()).isEmpty()) {
            throw new Rejected("C01", "턴: 단계 " + t.stepSeq() + " 없음");
        }
        Integer flowNo = t.flowNo();
        String factId = t.factId();
        if ("QUESTION".equals(t.kind())) {
            Catalog.Fact cat = k().catalog().fact(factId)
                    .orElseThrow(() -> new Rejected("C07", "질문 사실 " + t.factId()));
            checkFlow(t.caseId(), cat, flowNo);
            List<String> bad = t.choices().stream().filter(c -> !cat.hasOption(c)).toList();
            if (!bad.isEmpty()) {
                throw new Rejected("C08", "질문 보기 " + bad);
            }
        } else {
            Optional<Map<String, Object>> q = jdbc.sql(
                    "SELECT flow_no, fact_id FROM turns WHERE case_id=? AND turn_no=? AND kind='QUESTION'")
                    .params(t.caseId(), t.replyTo()).query().listOfRows().stream().findFirst();
            if (t.replyTo() == null || q.isEmpty()) {
                throw new Rejected("C04", "답이 받는 질문 턴 " + t.replyTo() + " 없음");
            }
            String qFact = (String) q.get().get("fact_id");
            Catalog.Fact cat = k().catalog().fact(qFact).orElseThrow(() -> new Rejected("C07", qFact));
            List<String> bad = t.choices().stream().filter(c -> !cat.hasOption(c)).toList();
            if (!bad.isEmpty()) {
                throw new Rejected("C08", "답 " + bad + "는 " + qFact + " 보기에 없음");
            }
            flowNo = q.get().get("flow_no") == null ? null : ((Number) q.get().get("flow_no")).intValue();
            factId = qFact;
        }
        Integer fFlow = flowNo;
        String storedFact = "QUESTION".equals(t.kind()) ? factId : null;
        String choices = dumps(t.choices());
        return write(() -> {
            int turnNo = jdbc.sql("SELECT COALESCE(MAX(turn_no),0)+1 FROM turns WHERE case_id=?").param(t.caseId())
                    .query(Integer.class).single();
            jdbc.sql("""
                    INSERT INTO turns (case_id, turn_no, role, kind, flow_no, fact_id, ask_no, reply_to, text,
                                       choices_json, step_seq, at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                    .params(t.caseId(), turnNo, t.role(), t.kind(), fFlow, storedFact, t.askNo(), t.replyTo(),
                            t.text(), choices, t.stepSeq(), t.at())
                    .update();
            return turnNo;
        });
    }

    private void checkFlow(String caseId, Catalog.Fact cat, Integer flowNo) {
        if (cat.common() && flowNo != null) {
            throw new Rejected("C10", cat.factId() + "는 공통 사실인데 흐름 " + flowNo);
        }
        if (!cat.common()) {
            if (flowNo == null) {
                throw new Rejected("C10", cat.factId() + "는 흐름 사실인데 흐름 없음");
            }
            if (!exists("SELECT 1 FROM flows WHERE case_id=? AND flow_no=?", caseId, flowNo)) {
                throw new Rejected("C10", "흐름 " + flowNo + " 없음");
            }
        }
    }

    // ───────── 사실 ─────────

    /** 저장 없이 사실 검사만(A1 업무 검사, 06 3.3). 거부면 Rejected. */
    public void checkFact(NewFact f) {
        validateFact(f);
    }

    public long addFact(NewFact f) {
        Validated v = validateFact(f);
        return write(() -> {
            int rev = jdbc.sql("""
                    SELECT COALESCE(MAX(rev),0)+1 FROM facts WHERE case_id=? AND fact_id=? AND flow_key=IFNULL(?,0)""")
                    .params(f.caseId(), f.factId(), f.flowNo()).query(Integer.class).single();
            GeneratedKeyHolder key = new GeneratedKeyHolder();
            jdbc.sql("""
                    INSERT INTO facts (case_id, flow_no, fact_id, rev, status, source_kind, source_turn, source_step,
                                       basis_quote, ledger_version, note, recorded_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                    .params(f.caseId(), f.flowNo(), f.factId(), rev, f.status(), f.sourceKind(), f.sourceTurn(),
                            f.sourceStep(), f.quote(), f.ledgerVersion(), f.note(), f.at())
                    .update(key, "fact_row_id");
            long id = key.getKey().longValue();
            int i = 1;
            for (Value val : v.values) {
                jdbc.sql("INSERT INTO fact_values (fact_row_id, fact_id, ord, label, detail) VALUES (?, ?, ?, ?, ?)")
                        .params(id, f.factId(), i++, val.label(), val.detail()).update();
            }
            i = 1;
            for (String rid : v.rows) {
                jdbc.sql("INSERT INTO fact_ledger_rows (fact_row_id, ord, row_id) VALUES (?, ?, ?)")
                        .params(id, i++, rid).update();
            }
            return id;
        });
    }

    private record Validated(List<Value> values, List<String> rows) {}

    private Validated validateFact(NewFact f) {
        Catalog.Fact cat = k().catalog().fact(f.factId()).orElseThrow(() -> new Rejected("C07", f.factId()));
        checkFlow(f.caseId(), cat, f.flowNo());
        String fid = f.factId();
        String src = f.sourceKind();
        // C01 출처
        if (!List.of("INQUIRY", "ANSWER", "LEDGER").contains(src == null ? "" : src)) {
            throw new Rejected("C01", fid + " 출처 '" + src + "'");
        }
        Optional<Map<String, Object>> step = step(f.caseId(), f.sourceStep());
        if (step.isEmpty()) {
            throw new Rejected("C01", fid + " 단계 " + f.sourceStep() + " 없음");
        }
        // C04 답변 턴
        String turnText = null;
        if ("ANSWER".equals(src)) {
            Optional<String> t = f.sourceTurn() == null ? Optional.empty() : jdbc.sql(
                    "SELECT text FROM turns WHERE case_id=? AND turn_no=? AND kind='ANSWER'")
                    .params(f.caseId(), f.sourceTurn()).query(String.class).optional();
            if (t.isEmpty()) {
                throw new Rejected("C04", fid + " 턴 " + f.sourceTurn());
            }
            turnText = t.get();
        } else if (f.sourceTurn() != null) {
            throw new Rejected("C04", fid + ": " + src + " 출처에 턴 " + f.sourceTurn());
        }
        // C08 값
        List<Value> vals = f.values() == null ? List.of() : f.values();
        for (Value v : vals) {
            Optional<Catalog.Option> o = cat.option(v.label());
            if (o.isEmpty()) {
                throw new Rejected("C08", fid + " 값 '" + v.label() + "'");
            }
            boolean detail = v.detail() != null && !v.detail().isEmpty();
            if ("DETAIL".equals(o.get().kind()) != detail) {
                throw new Rejected("C08", fid + " '" + v.label() + "': 자유 입력 "
                        + ("DETAIL".equals(o.get().kind()) ? "누락" : "잘못 붙음"));
            }
        }
        if (vals.size() > 1 && !cat.multi()) {
            throw new Rejected("C08", fid + "는 단일 사실인데 값 " + vals.size() + "개");
        }
        if (vals.stream().map(Value::label).distinct().count() != vals.size()) {
            throw new Rejected("C08", fid + " 값 중복");
        }
        Set<String> kinds = new HashSet<>();
        vals.forEach(v -> kinds.add(cat.option(v.label()).orElseThrow().kind()));
        // C09 확인 상태
        String status = f.status();
        if (!Catalog.STATUSES.contains(status == null ? "" : status)) {
            throw new Rejected("C09", fid + " 상태 '" + status + "'");
        }
        if ("NOT_FOUND".equals(status) != vals.isEmpty()) {
            throw new Rejected("C09", fid + ": 모름(NOT_FOUND)은 값이 없을 때만");
        }
        if (kinds.contains("UNKNOWN") && (vals.size() > 1 || !"UNKNOWN".equals(status))) {
            throw new Rejected("C09", fid + ": '잘 모르겠음'은 혼자, 상태는 현업 미확인");
        }
        if ("UNKNOWN".equals(status) && (!kinds.contains("UNKNOWN") || !"ANSWER".equals(src))) {
            throw new Rejected("C09", fid + ": 현업 미확인은 현업이 답 턴에서 '잘 모르겠음'을 고를 때만");
        }
        if ("LEDGER".equals(src)) {
            boolean explicit = vals.stream().anyMatch(v -> "MATCH".equals(cat.option(v.label()).orElseThrow().kind())
                    && v.label().contains(EXPLICIT_MARK));
            String want = explicit ? "MATCHED" : "INFERRED";
            if (!want.equals(status)) {
                throw new Rejected("C09", fid + ": 원장 출처 " + (explicit ? "명시 일치" : "포괄 일치·일치 없음") + "는 "
                        + want + " (지금 " + status + ")");
            }
        } else if ("MATCHED".equals(status)) {
            throw new Rejected("C09", fid + ": MATCHED는 원장 출처만");
        }
        // C05 원장 출처 조건
        List<String> att = cat.ledgerAttachments();
        List<String> rows = f.ledgerRows() == null ? List.of() : f.ledgerRows();
        if ("LEDGER".equals(src)) {
            if (att.isEmpty()) {
                throw new Rejected("C05", fid + "는 원장 조회가 없는 사실(" + cat.ledgerLookup() + ")");
            }
            if (!"A3".equals(step.get().get("step"))) {
                throw new Rejected("C05", fid + ": 원장 사실은 A3 단계가 적음(지금 " + step.get().get("step") + ")");
            }
            Set<String> nonNo = new HashSet<>(kinds);
            nonNo.remove("NO_MATCH");
            if (!nonNo.isEmpty() && rows.isEmpty()) {
                throw new Rejected("C05", fid + ": 일치 값인데 원장 행 없음");
            }
            if (kinds.equals(Set.of("NO_MATCH")) && !rows.isEmpty()) {
                throw new Rejected("C05", fid + ": 일치 없음인데 원장 행 " + rows);
            }
        } else if (!rows.isEmpty() || f.ledgerVersion() != null) {
            throw new Rejected("C05", fid + ": " + src + " 출처에 원장 행·판");
        }
        // C06 원장 행·판
        List<Ledger.Row> lrows = new ArrayList<>();
        if ("LEDGER".equals(src)) {
            if (f.ledgerVersion() == null || !k().ledger().hasVersion(f.ledgerVersion())) {
                throw new Rejected("C06", "처리방침 판 '" + f.ledgerVersion() + "'");
            }
            for (String rid : rows) {
                Ledger.Row r = k().ledger().row(rid).orElseThrow(() -> new Rejected("C06", "원장 행 " + rid));
                if (!r.versionId().equals(f.ledgerVersion())) {
                    throw new Rejected("C06", rid + " 판 " + r.versionId() + " ≠ " + f.ledgerVersion());
                }
                if (!att.contains(r.attachment())) {
                    throw new Rejected("C06", fid + "는 " + att + "만 볼 수 있음(" + rid + ")");
                }
                lrows.add(r);
            }
        }
        // C02·C03 근거 문구
        boolean needsQuote = List.of("CONFIRMED", "MATCHED", "INFERRED").contains(status) && !kinds.equals(Set.of("NO_MATCH"));
        String quote = f.quote();
        if (needsQuote && (quote == null || quote.isBlank())) {
            throw new Rejected("C02", fid);
        }
        if (quote != null && !quote.isEmpty()) {
            String text;
            if ("INQUIRY".equals(src)) {
                text = jdbc.sql("SELECT content FROM cases WHERE case_id=?").param(f.caseId()).query(String.class).single();
            } else if ("ANSWER".equals(src)) {
                text = turnText;
            } else {
                text = String.join("\n", lrows.stream().map(Ledger.Row::text).toList());
            }
            boolean ledger = "LEDGER".equals(src);   // PDF 줄바꿈 때문에 원장은 공백을 빼고 견준다
            String hay = ledger ? SPACE.matcher(text).replaceAll("") : text;
            for (String piece : quote.split(Pattern.quote(QUOTE_SEP), -1)) {
                String needle = ledger ? SPACE.matcher(piece).replaceAll("") : piece;
                if (!hay.contains(needle)) {
                    throw new Rejected("C03", fid + ": '" + piece + "'가 " + src + " 글에 없음");
                }
            }
        }
        return new Validated(vals, rows);
    }

    // ───────── 인용 해시 ─────────

    /** C13: 키가 가리키는 그 판의 지문이 맞나. */
    public boolean citationOk(String kind, String key, String textHash, String ledgerVersion) {
        if (key == null || textHash == null) {
            return false;
        }
        return switch (kind) {
            case "LAW_UNIT" -> k().laws().hashMatches(key, textHash);
            case "ANNEX" -> k().annexes().hashMatches(key, textHash);
            case "GUIDE_SECTION" -> k().sections().hash(key).map(textHash::equals).orElse(false);
            case "LEDGER_ROW" -> k().ledger().row(key)
                    .map(r -> r.versionId().equals(ledgerVersion) && r.textHash().equals(textHash)).orElse(false);
            default -> false;
        };
    }

    // ───────── 판정 ─────────

    public long addJudgment(NewJudgment j) {
        String caseId = j.caseId();
        // C16 연결
        Optional<Map<String, Object>> st = step(caseId, j.stepSeq());
        if (st.isEmpty() || !"A8".equals(st.get().get("step"))) {
            throw new Rejected("C16", "판정 단계 " + j.stepSeq() + "는 A8이 아님");
        }
        if (j.judgedTurn() != null && !exists("SELECT 1 FROM turns WHERE case_id=? AND turn_no=?", caseId, j.judgedTurn())) {
            throw new Rejected("C16", "턴 " + j.judgedTurn() + " 없음");
        }
        // C11 꾸러미 판
        if (j.packageVersion() == null || j.packageVersion().isEmpty()) {
            throw new Rejected("C11", "꾸러미 판 없음");
        }
        if (!k().packages().hasVersion(j.packageVersion())) {
            throw new Rejected("C11", "꾸러미 판 '" + j.packageVersion() + "'가 지식에 없음");
        }
        // C12 기준 판
        if (!Objects.equals(j.ruleSetVersion(), k().catalog().ruleSetVersion())) {
            throw new Rejected("C12", "규칙 세트 '" + j.ruleSetVersion() + "'");
        }
        Map<String, Object> pin = jdbc.sql("SELECT rule_set_version, package_version, ledger_version FROM cases WHERE case_id=?")
                .param(caseId).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> new Rejected("C16", "사안 " + caseId + " 없음"));
        if (!Objects.equals(j.ruleSetVersion(), pin.get("rule_set_version"))) {
            throw new Rejected("C12", "규칙 세트 '" + j.ruleSetVersion() + "' ≠ 사안 고정값 '" + pin.get("rule_set_version") + "'");
        }
        if (!Objects.equals(j.packageVersion(), pin.get("package_version"))) {
            throw new Rejected("C12", "꾸러미 판 '" + j.packageVersion() + "' ≠ 사안 고정값 '" + pin.get("package_version") + "'");
        }
        if (j.ledgerVersion() != null && !Objects.equals(j.ledgerVersion(), pin.get("ledger_version"))) {
            throw new Rejected("C12", "처리방침 판 '" + j.ledgerVersion() + "' ≠ 사안 고정값 '" + pin.get("ledger_version") + "'");
        }
        if (!Objects.equals(j.factMapSha256(), k().catalog().factMapSha256())) {
            throw new Rejected("C12", "대응표 해시가 기준표와 다름");
        }
        Set<String> used = new LinkedHashSet<>(jdbc.sql(
                "SELECT DISTINCT ledger_version FROM facts WHERE case_id=? AND ledger_version IS NOT NULL")
                .param(caseId).query(String.class).list());
        List<CitationEntry> cits = j.citations() == null ? List.of() : j.citations();
        cits.stream().filter(c -> "LEDGER_ROW".equals(c.kind())).forEach(c -> used.add(c.ledgerVersion()));
        if (!used.isEmpty() && !(used.size() == 1 && used.contains(j.ledgerVersion()))) {
            throw new Rejected("C12", "처리방침 판 '" + j.ledgerVersion() + "', 원장 사실·인용은 " + used);
        }
        if (j.ledgerVersion() != null && !k().ledger().hasVersion(j.ledgerVersion())) {
            throw new Rejected("C12", "처리방침 판 '" + j.ledgerVersion() + "'가 처리 원장에 없음");
        }
        // C17 프롬프트·모델
        List<Map<String, Object>> llm = jdbc.sql("""
                SELECT DISTINCT prompt_id, prompt_version, model_name, temperature FROM steps
                WHERE case_id=? AND actor='LLM'""").param(caseId).query().listOfRows();
        Set<List<String>> stepPrompts = new HashSet<>();
        llm.forEach(r -> stepPrompts.add(List.of((String) r.get("prompt_id"), (String) r.get("prompt_version"))));
        Set<List<String>> given = new HashSet<>();
        (j.promptVersions() == null ? List.<CaseInput.PromptRef>of() : j.promptVersions())
                .forEach(p -> given.add(List.of(p.promptId(), p.version())));
        if (!given.equals(stepPrompts)) {
            throw new Rejected("C17", "판정의 프롬프트 목록이 이 사안 LLM 단계와 다름");
        }
        for (Map<String, Object> r : llm) {
            if (!Objects.equals(r.get("model_name"), j.modelName())
                    || j.temperature() == null || ((BigDecimal) r.get("temperature")).compareTo(j.temperature()) != 0) {
                throw new Rejected("C17", "판정의 모델·온도가 LLM 단계와 다름");
            }
        }
        // C14 슬롯
        Set<Integer> flows = new HashSet<>(jdbc.sql("SELECT flow_no FROM flows WHERE case_id=?").param(caseId)
                .query(Integer.class).list());
        List<SlotEntry> slots = j.slots() == null ? List.of() : j.slots();
        Map<Integer, List<String>> seen = new LinkedHashMap<>();
        for (SlotEntry s : slots) {
            Catalog.Slot def = k().catalog().slot(s.slot()).orElseThrow(() -> new Rejected("C14", "슬롯 '" + s.slot() + "'"));
            if (!flows.contains(s.flow())) {
                throw new Rejected("C10", "슬롯 " + s.slot() + ": 흐름 " + s.flow() + " 없음");
            }
            List<String> bad = s.values().stream().filter(x -> !def.values().contains(x)).toList();
            if (!bad.isEmpty()) {
                throw new Rejected("C14", s.slot() + " 값 " + bad);
            }
            if (!def.multi() && s.values().size() > 1) {
                throw new Rejected("C14", s.slot() + "는 단일 슬롯인데 값 " + s.values());
            }
            boolean v = !s.values().isEmpty(), u = !s.unknownFacts().isEmpty();
            Boolean ok = switch (s.status() == null ? "" : s.status()) {
                case "CONFIRMED" -> v && !u;
                case "PARTIAL" -> def.multi() && v && u;
                case "UNKNOWN" -> !v && u;
                case "SKIPPED" -> !v && !u;
                default -> null;
            };
            if (ok == null || !ok) {
                throw new Rejected("C14", "흐름 " + s.flow() + " " + s.slot() + ": 상태 " + s.status() + "와 값 "
                        + s.values() + "·미확인 " + s.unknownFacts() + "가 안 맞음");
            }
            seen.computeIfAbsent(s.flow(), x -> new ArrayList<>()).add(s.slot());
            // C16 근거 사실
            for (long fr : s.basisFacts()) {
                Optional<Map<String, Object>> r = jdbc.sql("SELECT case_id, flow_no FROM facts WHERE fact_row_id=?")
                        .param(fr).query().listOfRows().stream().findFirst();
                Object rf = r.map(x -> x.get("flow_no")).orElse(null);
                if (r.isEmpty() || !caseId.equals(r.get().get("case_id"))
                        || !(rf == null || ((Number) rf).intValue() == s.flow())) {
                    throw new Rejected("C16", "흐름 " + s.flow() + " " + s.slot() + ": 근거 사실 " + fr + "는 이 사안·흐름 것이 아님");
                }
            }
            for (String fid : s.unknownFacts()) {
                if (!exists("""
                        SELECT 1 FROM facts_current WHERE case_id=? AND fact_id=? AND (flow_no=? OR flow_no IS NULL)
                          AND status='UNKNOWN'""", caseId, fid, s.flow())) {
                    throw new Rejected("C16", "흐름 " + s.flow() + " " + s.slot() + ": " + fid + "는 현업 미확인 사실이 아님");
                }
            }
        }
        Set<String> allSlots = new HashSet<>();
        k().catalog().slots().forEach(x -> allSlots.add(x.slotKey()));
        for (var e : seen.entrySet()) {
            if (e.getValue().size() != allSlots.size() || !new HashSet<>(e.getValue()).equals(allSlots)) {
                throw new Rejected("C14", "흐름 " + e.getKey() + " 슬롯 " + e.getValue() + " ≠ " + allSlots.size() + "개");
            }
        }
        if (!slots.isEmpty() && !seen.keySet().equals(flows)) {
            Set<Integer> missing = new HashSet<>(flows);
            missing.removeAll(seen.keySet());
            throw new Rejected("C14", "슬롯이 없는 흐름 " + missing);
        }
        // C15 쟁점
        List<IssueEntry> issues = j.issues() == null ? List.of() : j.issues();
        String top = null;
        for (IssueEntry x : issues) {
            Packages.Package p = k().packages().get(j.packageVersion(), x.packageId())
                    .filter(pp -> pp.issueName().equals(x.issue()))
                    .orElseThrow(() -> new Rejected("C15", j.packageVersion() + " " + x.packageId() + " '" + x.issue() + "'"));
            if (!flows.contains(x.flow())) {
                throw new Rejected("C10", "쟁점 " + x.issue() + ": 흐름 " + x.flow() + " 없음");
            }
            List<String> badC = x.conditions().stream().filter(c -> !p.conditionIds().contains(c)).sorted().toList();
            if (!badC.isEmpty()) {
                throw new Rejected("C15", x.issue() + ": 조건 " + badC);
            }
            List<String> badI = x.interpFlags().stream().filter(c -> !p.interpIssueIds().contains(c)).sorted().toList();
            if (!badI.isEmpty()) {
                throw new Rejected("C15", x.issue() + ": 해석 쟁점 " + badI + "는 " + j.packageVersion() + "에서 해석 쟁점 아님");
            }
            List<String> badR = x.ruleIds().stream().filter(r -> k().ruleSet().rule(r).isEmpty()).sorted().toList();
            if (!badR.isEmpty()) {
                throw new Rejected("C15", "규칙 " + badR);
            }
            if (top == null || "HML".indexOf(x.grade()) < "HML".indexOf(top)) {
                top = x.grade();
            }
        }
        if (!Objects.equals(j.caseGrade(), top)) {
            throw new Rejected("C15", "사안 등급 " + j.caseGrade() + " ≠ 쟁점 최고 " + top);
        }
        // C13 인용
        for (CitationEntry c : cits) {
            if (!citationOk(c.kind(), c.key(), c.textHash(), c.ledgerVersion())) {
                throw new Rejected("C13", c.kind() + " " + c.key() + " "
                        + (c.textHash() == null ? null : c.textHash().substring(0, Math.min(12, c.textHash().length()))));
            }
            if (c.issue() != null && !(c.issue() >= 0 && c.issue() < issues.size())) {
                throw new Rejected("C13", c.key() + ": 쟁점 번호 " + c.issue());
            }
        }

        return write(() -> {
            GeneratedKeyHolder key = new GeneratedKeyHolder();
            jdbc.sql("""
                    INSERT INTO case_judgments (case_id, step_seq, judged_turn, reference_date, rule_set_version,
                        package_version, ledger_version, fact_map_sha256, model_name, temperature, prompt_versions,
                        case_grade, exit_type, exit_reasons, conclusive, flags, review_bundle, premise_note, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                    .params(caseId, j.stepSeq(), j.judgedTurn(), j.referenceDate(), j.ruleSetVersion(),
                            j.packageVersion(), j.ledgerVersion(), j.factMapSha256(), j.modelName(), j.temperature(),
                            dumps(j.promptVersions().stream().map(p -> Map.of("prompt_id", p.promptId(),
                                    "version", p.version())).toList()),
                            j.caseGrade(), j.exitType(), dumps(j.exitReasons()), j.conclusive(), dumps(j.flags()),
                            j.reviewBundle() == null ? null : dumps(j.reviewBundle()),
                            j.premiseNote() == null ? NewJudgment.PREMISE_NOTE : j.premiseNote(), j.createdAt())
                    .update(key, "judgment_id");
            long jid = key.getKey().longValue();
            for (SlotEntry s : slots) {
                jdbc.sql("""
                        INSERT INTO case_judgment_slots (judgment_id, case_id, flow_no, slot_key, status, basis_facts,
                                                         unknown_facts) VALUES (?, ?, ?, ?, ?, ?, ?)""")
                        .params(jid, caseId, s.flow(), s.slot(), s.status(), dumps(s.basisFacts()), dumps(s.unknownFacts()))
                        .update();
                int i = 1;
                for (String v : s.values()) {
                    jdbc.sql("INSERT INTO case_judgment_slot_values (judgment_id, flow_no, slot_key, ord, value) VALUES (?, ?, ?, ?, ?)")
                            .params(jid, s.flow(), s.slot(), i++, v).update();
                }
            }
            List<Long> ids = new ArrayList<>();
            for (IssueEntry x : issues) {
                GeneratedKeyHolder ik = new GeneratedKeyHolder();
                jdbc.sql("""
                        INSERT INTO case_judgment_issues (judgment_id, flow_no, issue_name, package_id, grade, rule_ids,
                            conditions, interp_flags, conclusive, opinion, actions, visible_to_requester)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                        .params(jid, x.flow(), x.issue(), x.packageId(), x.grade(), dumps(x.ruleIds()),
                                dumps(x.conditions()), dumps(x.interpFlags()), x.conclusive(), x.opinion(), x.actions(),
                                x.visible())
                        .update(ik, "issue_row_id");
                ids.add(ik.getKey().longValue());
            }
            int i = 1;
            for (CitationEntry c : cits) {
                jdbc.sql("""
                        INSERT INTO case_judgment_citations (judgment_id, issue_row_id, ord, cite_kind, cite_key,
                            text_hash, ledger_version, origin) VALUES (?, ?, ?, ?, ?, ?, ?, ?)""")
                        .params(jid, c.issue() == null ? null : ids.get(c.issue()), i++, c.kind(), c.key(),
                                c.textHash(), c.ledgerVersion(), c.origin())
                        .update();
            }
            return jid;
        });
    }

    // ───────── 작은 조회 ─────────

    private Optional<Map<String, Object>> step(String caseId, int seq) {
        return jdbc.sql("SELECT step, actor, name, flow_no FROM steps WHERE case_id=? AND seq=?").params(caseId, seq)
                .query().listOfRows().stream().findFirst();
    }

    private boolean exists(String sql, Object... args) {
        return !jdbc.sql(sql).params(args).query().listOfRows().isEmpty();
    }

    /** 근거 사실 ID → 지금 판 fact_row_id(흐름 사실이면 그 흐름, 없으면 공통). 판정 슬롯 basis_facts를 만들 때. */
    public Optional<Long> currentFactRow(String caseId, Integer flowNo, String factId) {
        return jdbc.sql("""
                SELECT fact_row_id FROM facts_current WHERE case_id=? AND fact_id=? AND (flow_no=? OR flow_no IS NULL)
                ORDER BY flow_no IS NULL LIMIT 1""").params(caseId, factId, flowNo).query(Long.class).optional();
    }

    static Map<String, Object> fields(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }
}
