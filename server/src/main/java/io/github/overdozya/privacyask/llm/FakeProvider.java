package io.github.overdozya.privacyask.llm;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.function.Function;

/**
 * LLM_PROVIDER=fake(06 4.1). 손으로 쓴 출력·기록해 둔 출력을 차례로 돌려준다. 받은 요청은 requests()로 본다.
 * 차례가 비면 fallback을 쓴다(기본: 빈 JSON 객체, STOP).
 */
public class FakeProvider implements LlmProvider {
    private final Deque<Function<ProviderRequest, ProviderResponse>> script = new ArrayDeque<>();
    private final List<ProviderRequest> requests = Collections.synchronizedList(new ArrayList<>());
    private volatile Function<ProviderRequest, ProviderResponse> fallback = r -> ok("{}");

    @Override
    public String name() {
        return "fake";
    }

    public static ProviderResponse ok(String text) {
        return new ProviderResponse(text, "STOP", 10, 5, 0, 200, 1, "fake-1");
    }

    public static ProviderResponse finish(String text, String finishReason) {
        return new ProviderResponse(text, finishReason, 10, 5, 0, 200, 1, "fake-1");
    }

    public synchronized FakeProvider thenText(String text) {
        script.add(r -> ok(text));
        return this;
    }

    public synchronized FakeProvider thenResponse(ProviderResponse response) {
        script.add(r -> response);
        return this;
    }

    public synchronized FakeProvider thenThrow(ProviderException e) {
        script.add(r -> { throw e; });
        return this;
    }

    public synchronized FakeProvider then(Function<ProviderRequest, ProviderResponse> step) {
        script.add(step);
        return this;
    }

    public FakeProvider fallback(Function<ProviderRequest, ProviderResponse> f) {
        this.fallback = f;
        return this;
    }

    public List<ProviderRequest> requests() {
        return List.copyOf(requests);
    }

    @Override
    public ProviderResponse generate(ProviderRequest request) {
        requests.add(request);
        Function<ProviderRequest, ProviderResponse> step;
        synchronized (this) {
            step = script.poll();
        }
        return (step != null ? step : fallback).apply(request);
    }
}
