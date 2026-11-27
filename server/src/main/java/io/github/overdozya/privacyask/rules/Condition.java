package io.github.overdozya.privacyask.rules;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import tools.jackson.databind.JsonNode;

/**
 * 조건식 하나의 해석기(06 3.2). 현업 규칙 16, 꾸러미 trigger·세부 조건·해석 쟁점, 슬롯 변환 규칙이 모두 이것을 쓴다.
 * 뜻은 S3 슬롯변환_v1 '의미' 절과 build/case/slot_rules.py(ev·support)와 같다.
 *
 * <ul>
 *   <li>결합: all · any · not, 3값(클리니). 빈 all은 참, 빈 any는 거짓.</li>
 *   <li>fact {fact, op, value}: 상태가 CONFIRMED·MATCHED·INFERRED면 값 목록으로 참/거짓, 그 밖은 모름(모름 사실 = 그 사실).</li>
 *   <li>status {fact, op: status, value: 글 또는 목록}: 상태가 그 값(목록이면 그중 하나)인가. 늘 참/거짓.</li>
 *   <li>slot {slot, op, value}: CONFIRMED·PARTIAL이면 아는 값으로 참/거짓, 그 밖은 거짓. 늘 참/거짓.</li>
 *   <li>ledger {ledger, column, op, value}: L 사실이 MATCHED면 기댄 행들 중 하나의 그 칸 글(공백 뺌)에 value가 있나.
 *       값이 있는 다른 상태면 거짓, UNKNOWN·NOT_FOUND면 모름.</li>
 *   <li>연산자 eq · in · contains · contains_any(현업 넷).</li>
 * </ul>
 */
public final class Condition {
    private static final Pattern WS = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);

    /** 값과, 값이 모름일 때 모름을 만든 사실(나머지 때는 빈 집합). */
    public record Result(Tri value, Set<String> unknownFacts) {
        static final Result TRUE = new Result(Tri.TRUE, Set.of());
        static final Result FALSE = new Result(Tri.FALSE, Set.of());

        static Result of(boolean b) {
            return b ? TRUE : FALSE;
        }
    }

    private Condition() {}

    public static Result eval(JsonNode node, EvalContext ctx) {
        if (node.has("all") || node.has("any")) {
            boolean all = node.has("all");
            JsonNode kids = node.get(all ? "all" : "any");
            Tri stop = all ? Tri.FALSE : Tri.TRUE;
            Set<String> unk = new LinkedHashSet<>();
            boolean anyUnknown = false;
            List<Result> rs = new ArrayList<>();
            for (JsonNode c : kids) {
                rs.add(eval(c, ctx));
            }
            for (Result r : rs) {
                if (r.value() == stop) {
                    return all ? Result.FALSE : Result.TRUE;
                }
            }
            for (Result r : rs) {
                if (r.value() == Tri.UNKNOWN) {
                    anyUnknown = true;
                    unk.addAll(r.unknownFacts());
                }
            }
            if (anyUnknown) {
                return new Result(Tri.UNKNOWN, Collections.unmodifiableSet(unk));
            }
            return all ? Result.TRUE : Result.FALSE;
        }
        if (node.has("not")) {
            Result r = eval(node.get("not"), ctx);
            return r.value() == Tri.UNKNOWN ? r : Result.of(r.value() == Tri.FALSE);
        }
        if (node.has("slot")) {
            EvalContext.SlotView s = ctx.slot(node.get("slot").asString());
            if (!s.usable()) {
                return Result.FALSE;
            }
            return Result.of(match(op(node), s.values(), node.get("value")));
        }
        String f = factOf(node);
        if (f == null) {
            throw new IllegalArgumentException("모르는 식: " + node);
        }
        FactState cur = ctx.fact(f);
        if ("status".equals(op(node))) {
            return Result.of(strings(node.get("value")).contains(cur.status()));
        }
        if (!cur.known()) {
            return new Result(Tri.UNKNOWN, Set.of(f));
        }
        if (node.has("ledger")) {
            if (!FactState.MATCHED.equals(cur.status())) {
                return Result.FALSE;
            }
            List<String> want = strings(node.get("value"));
            String col = squash(node.get("column").asString());
            for (String rid : cur.rows()) {
                for (EvalContext.Column c : ctx.ledgerRow(rid)) {
                    if (squash(c.name()).equals(col)) {
                        String t = squash(c.text());
                        for (String w : want) {
                            if (t.contains(squash(w))) {
                                return Result.TRUE;
                            }
                        }
                    }
                }
            }
            return Result.FALSE;
        }
        return Result.of(match(op(node), cur.values(), node.get("value")));
    }

    /** 2값으로 쓸 때(규칙 엔진·trigger·꾸러미 조건): 참일 때만 참, 모름은 거짓. */
    public static boolean holds(JsonNode node, EvalContext ctx) {
        return eval(node, ctx).value() == Tri.TRUE;
    }

    /**
     * 값이 want(참/거짓)로 정해진 식에서 그 값을 만든 사실(slot_rules.py support). all이 참이면 모든 자식, 거짓이면 거짓인 자식.
     * any가 참이면 참인 자식, 거짓이면 모든 자식. not은 안쪽을 반대로. 판이 없는(NOT_FOUND) 사실과 슬롯 원자는 넣지 않는다.
     */
    public static Set<String> support(JsonNode node, EvalContext ctx, boolean want) {
        Set<String> out = new LinkedHashSet<>();
        support(node, ctx, want, out);
        return out;
    }

    private static void support(JsonNode node, EvalContext ctx, boolean want, Set<String> out) {
        if (node.has("all") || node.has("any")) {
            boolean every = node.has("all") == want;
            Tri w = Tri.of(want);
            for (JsonNode c : node.get(node.has("all") ? "all" : "any")) {
                if (every || eval(c, ctx).value() == w) {
                    support(c, ctx, want, out);
                }
            }
            return;
        }
        if (node.has("not")) {
            support(node.get("not"), ctx, !want, out);
            return;
        }
        String f = factOf(node);
        if (f != null && !ctx.fact(f).notFound()) {
            out.add(f);
        }
    }

    // ───────── 걷기 ─────────

    /** 원자(잎)를 식 순서대로. */
    public static List<JsonNode> atoms(JsonNode node) {
        List<JsonNode> out = new ArrayList<>();
        atoms(node, out);
        return out;
    }

    private static void atoms(JsonNode node, List<JsonNode> out) {
        if (node.has("all") || node.has("any")) {
            for (JsonNode c : node.get(node.has("all") ? "all" : "any")) {
                atoms(c, out);
            }
        } else if (node.has("not")) {
            atoms(node.get("not"), out);
        } else {
            out.add(node);
        }
    }

    /** 식이 가리키는 사실 ID(fact·status·ledger 원자), 나온 순서, 겹치면 하나. */
    public static Set<String> refFacts(JsonNode node) {
        Set<String> out = new LinkedHashSet<>();
        for (JsonNode a : atoms(node)) {
            String f = factOf(a);
            if (f != null) {
                out.add(f);
            }
        }
        return out;
    }

    public static Set<String> refSlots(JsonNode node) {
        Set<String> out = new LinkedHashSet<>();
        for (JsonNode a : atoms(node)) {
            if (a.has("slot")) {
                out.add(a.get("slot").asString());
            }
        }
        return out;
    }

    static String factOf(JsonNode atom) {
        if (atom.has("fact")) {
            return atom.get("fact").asString();
        }
        if (atom.has("ledger")) {
            return atom.get("ledger").asString();
        }
        return null;
    }

    private static String op(JsonNode node) {
        return node.get("op").asString();
    }

    /** 현업 연산자 넷(slot_rules.py _match). */
    static boolean match(String op, List<String> have, JsonNode want) {
        return switch (op) {
            case "eq" -> have.size() == 1 && have.get(0).equals(want.asString());
            case "in" -> have.size() == 1 && strings(want).contains(have.get(0));
            case "contains" -> have.contains(want.asString());
            case "contains_any" -> strings(want).stream().anyMatch(have::contains);
            default -> throw new IllegalArgumentException("모르는 연산자: " + op);
        };
    }

    /** 글 하나 또는 글 목록 → 목록. */
    public static List<String> strings(JsonNode v) {
        if (v == null || v.isNull() || v.isMissingNode()) {
            return List.of();
        }
        if (v.isArray()) {
            List<String> out = new ArrayList<>();
            for (JsonNode x : v) {
                out.add(x.asString());
            }
            return out;
        }
        return List.of(v.asString());
    }

    static String squash(String s) {
        return WS.matcher(s).replaceAll("");
    }
}
