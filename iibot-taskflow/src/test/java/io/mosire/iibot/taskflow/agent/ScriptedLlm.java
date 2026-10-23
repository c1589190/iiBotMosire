package io.mosire.iibot.taskflow.agent;

import io.mosire.agentlib.llm.LlmClient;
import io.mosire.agentlib.llm.LlmException;
import io.mosire.agentlib.llm.LlmRequest;
import io.mosire.agentlib.llm.LlmResponse;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** 完全离线的脚本化 LlmClient，并记录每次请求用于可见性断言。 */
final class ScriptedLlm implements LlmClient {

    private final Deque<LlmResponse> script = new ArrayDeque<>();
    private final List<LlmRequest> requests = new ArrayList<>();

    static ScriptedLlm with(LlmResponse... responses) {
        ScriptedLlm llm = new ScriptedLlm();
        for (LlmResponse response : responses) {
            llm.enqueue(response);
        }
        return llm;
    }

    void enqueue(LlmResponse response) {
        script.addLast(response);
    }

    List<LlmRequest> requests() {
        return List.copyOf(requests);
    }

    String allPromptText() {
        StringBuilder text = new StringBuilder();
        for (LlmRequest request : requests) {
            request.messages().forEach(message -> message.content()
                    .forEach(part -> text.append(part).append('\n')));
        }
        return text.toString();
    }

    @Override
    public LlmResponse chat(LlmRequest request) {
        requests.add(request);
        LlmResponse response = script.pollFirst();
        if (response == null) {
            throw new LlmException("测试脚本已耗尽");
        }
        return response;
    }
}
