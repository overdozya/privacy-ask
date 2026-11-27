package io.github.overdozya.privacyask.llm;

import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import io.github.overdozya.privacyask.common.AppClock;

/**
 * app.llm.ping-on-startup=true면 기동 뒤 S0_ping 한 번(06 7절 S2 ⑤). 입력은 지어낸 짧은 글(문의 문장 아님).
 * 결과는 llm_call_logs에 남고(case_id NULL), 로그 한 줄로 낸다.
 */
@Component
@ConditionalOnProperty(name = "app.llm.ping-on-startup", havingValue = "true")
public class PingRunner implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(PingRunner.class);

    private final LlmGateway gateway;
    private final AppClock clock;

    public PingRunner(LlmGateway gateway, AppClock clock) {
        this.gateway = gateway;
        this.clock = clock;
    }

    public record Pong(String status, String echo) {}

    @Override
    public void run(ApplicationArguments args) {
        LlmResult<Pong> r = ping();
        log.info("S0_ping status={} attempts={} model={} prompt={} call_ids={} error={}", r.status(), r.attempts(),
                r.model(), r.promptVersion(), r.callIds(), r.lastError());
    }

    public LlmResult<Pong> ping() {
        String nonce = HexFormat.of().formatHex(new byte[] {
                (byte) ThreadLocalRandom.current().nextInt(), (byte) ThreadLocalRandom.current().nextInt(),
                (byte) ThreadLocalRandom.current().nextInt(), (byte) ThreadLocalRandom.current().nextInt() });
        Map<String, String> input = Map.of("purpose", "기동 확인", "nonce", nonce);
        return gateway.call("S0_ping", input, Pong.class,
                p -> nonce.equals(p.echo()) ? null : "echo가 nonce와 다름",
                CallContext.standalone(clock.now().plus(Duration.ofSeconds(60))));
    }

}
