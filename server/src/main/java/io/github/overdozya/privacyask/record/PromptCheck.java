package io.github.overdozya.privacyask.record;

/** C17 더함(06 2.6): LLM 단계의 프롬프트 ID·버전이 실행 중 PromptCatalog에 있나. */
@FunctionalInterface
public interface PromptCheck {
    boolean known(String promptId, String promptVersion);

    /** 기록을 다시 넣을 때(예시 사안 재현처럼 지금 프롬프트 파일과 판이 다른 기록). 실행 경로에서는 쓰지 않는다. */
    PromptCheck ANY = (id, v) -> true;
}
