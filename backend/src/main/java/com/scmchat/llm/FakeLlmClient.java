package com.scmchat.llm;

import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 모델 없이 동작하는 기본 클라이언트. 큐에 넣어 둔 응답을 format과 무관하게 순서대로 돌려주고,
 * 큐가 비면 format에 맞는 기본 응답을 준다.
 */
@Component
@ConditionalOnProperty(name = "chat.llm.provider", havingValue = "fake", matchIfMissing = true)
public class FakeLlmClient implements LlmClient {

    public static final String DEFAULT_REPLY = "{\"action\":\"clarify\",\"sql\":\"\",\"message\":\"fake\"}";
    public static final String DEFAULT_ROUTE_REPLY = "{\"route\":\"clarify\",\"message\":\"fake\"}";
    public static final String DEFAULT_ANSWER_REPLY = "{\"answer\":\"\"}";

    private final Queue<String> queuedReplies = new ConcurrentLinkedQueue<>();

    public void enqueue(String... rawReplies) {
        queuedReplies.addAll(List.of(rawReplies));
    }

    @Override
    public LlmReply generate(List<LlmMessage> messages, OutputFormat format) {
        String queued = queuedReplies.poll();
        return new LlmReply(queued != null ? queued : defaultReply(format), 0, 0);
    }

    private static String defaultReply(OutputFormat format) {
        return switch (format) {
            case ACTION -> DEFAULT_REPLY;
            case ROUTE -> DEFAULT_ROUTE_REPLY;
            case ANSWER -> DEFAULT_ANSWER_REPLY;
        };
    }

    @Override
    public String modelName() {
        return "fake";
    }
}
