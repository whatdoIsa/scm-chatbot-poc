package com.scmchat.llm;

import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 모델 없이 동작하는 기본 클라이언트. 큐에 넣어 둔 응답을 순서대로 돌려주고, 큐가 비면 기본 응답을 준다. */
@Component
@ConditionalOnProperty(name = "chat.llm.provider", havingValue = "fake", matchIfMissing = true)
public class FakeLlmClient implements LlmClient {

    public static final String DEFAULT_REPLY = "{\"action\":\"clarify\",\"sql\":\"\",\"message\":\"fake\"}";

    private final Queue<String> queuedReplies = new ConcurrentLinkedQueue<>();

    public void enqueue(String... rawReplies) {
        queuedReplies.addAll(List.of(rawReplies));
    }

    @Override
    public LlmReply generate(List<LlmMessage> messages) {
        String queued = queuedReplies.poll();
        return new LlmReply(queued != null ? queued : DEFAULT_REPLY, 0, 0);
    }

    @Override
    public String modelName() {
        return "fake";
    }
}
