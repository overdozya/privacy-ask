package io.github.overdozya.privacyask.knowledge;

import java.util.List;

/** 기동 검사(06 3.1)를 어김: 적재를 잊었거나 일부만 넣었거나 파일 사이 참조가 빠짐. 기동을 멈춘다. */
public class KnowledgeLoadException extends RuntimeException {
    private final List<String> problems;

    public KnowledgeLoadException(List<String> problems) {
        super("지식 스냅샷 기동 검사 실패 " + problems.size() + "건: "
                + String.join("; ", problems.subList(0, Math.min(10, problems.size())))
                + (problems.size() > 10 ? " …" : ""));
        this.problems = List.copyOf(problems);
    }

    public List<String> problems() {
        return problems;
    }
}
