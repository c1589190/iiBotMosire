package io.mosire.iibot.app.support;

import io.mosire.agentlib.llm.LlmClient;
import io.mosire.agentlib.llm.LlmException;
import io.mosire.agentlib.llm.LlmRequest;
import io.mosire.agentlib.llm.LlmResponse;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** 完全离线的脚本化 LLM，记录请求并拒绝网络/真实模型调用。 */
public final class FakeLlmClient implements LlmClient {

    private final Deque<LlmResponse> script = new ArrayDeque<>();
    private final List<LlmRequest> requests = new ArrayList<>();

    public static FakeLlmClient with(LlmResponse... responses) {
        FakeLlmClient client = new FakeLlmClient();
        for (LlmResponse response : responses) {
            client.enqueue(response);
        }
        return client;
    }

    public synchronized void enqueue(LlmResponse response) {
        script.addLast(response);
    }

    public synchronized List<LlmRequest> requests() {
        return List.copyOf(requests);
    }

    @Override
    public synchronized LlmResponse chat(LlmRequest request) {
        requests.add(request);
        LlmResponse response = script.pollFirst();
        if (response == null) {
            throw new LlmException("测试脚本已耗尽");
        }
        return response;
    }
}
