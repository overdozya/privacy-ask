package io.github.overdozya.privacyask.common;

/**
 * 빌드 때 넣은 코드 판(git 짧은 해시, 고친 파일이 있으면 -dirty). steps.code_version에 쓴다(06 4.4).
 */
public record CodeVersion(String value) {
    public CodeVersion {
        if (value == null || value.isBlank() || value.contains("${")) {
            value = "unknown";
        }
    }
}
