package io.github.overdozya.privacyask.flow;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * app.flow.* (06 6.1·6.3). speculativeA1: 첫 요청에서 A0 ‖ A1을 같이 띄움. turnTimeout: 한 턴 상한(현업 turn_timeout_sec 60).
 */
@ConfigurationProperties("app.flow")
public record FlowProperties(
        @DefaultValue("true") boolean speculativeA1,
        @DefaultValue("60s") Duration turnTimeout) {}
