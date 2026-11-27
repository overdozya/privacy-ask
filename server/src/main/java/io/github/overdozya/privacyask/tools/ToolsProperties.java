package io.github.overdozya.privacyask.tools;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * app.tools.* — A6 보조 검색 도구(03 4.2 A6, 06 1.5 패키지 17).
 *
 * @param enabled         A6을 켬. 끄면 A5는 도구를 받지 않고 gap 해석 필요·꾸러미 밖은 지금처럼 이관(꾸러미만으로 충분한지 재기 위해 끌 수 있게, 03 A6)
 * @param maxCallsPerCase 문의(사안)당 도구 호출 상한(03 A6 "최대 2회"). search_guides·read_section 모두 센다
 * @param searchSections  search_guides가 돌려주고 글까지 붙이는 절 수
 * @param readMaxChars    절 하나에 붙이는 글자 상한(넘으면 앞부분만, truncated 표시. 해시는 절 전체)
 * @param modelDir        질의 임베딩 ONNX 폴더(model_int8pc.onnx·tokenizer.json). 비우면 {app.data-dir}/models/kure-v3-onnx.
 *                        없으면 글자 검색만으로 돈다(단계 기록 mode TEXT_ONLY)
 * @param warmup          기동 뒤 모델을 미리 띄움(벡터가 적재돼 있을 때만, 따로 스레드)
 * @param reduceInput     A5 입력 줄이기(S10): '해석' 역할 안내서 절은 키·경로·제목만 보내고 글은 read_section으로 읽게 함.
 *                        기본 켬(S10b 메인 결정, 최종은 평가에서 켬·끔을 견줘 다시 정함)
 */
@ConfigurationProperties("app.tools")
public record ToolsProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("2") int maxCallsPerCase,
        @DefaultValue("3") int searchSections,
        @DefaultValue("6000") int readMaxChars,
        String modelDir,
        @DefaultValue("true") boolean warmup,
        @DefaultValue("true") boolean reduceInput) {}
