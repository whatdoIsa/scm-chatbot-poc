package com.scmchat.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scmchat.config.EvalProperties;
import com.scmchat.eval.GoldenItem;
import com.scmchat.eval.GoldenSetLoader;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 골든셋의 정답을 그대로 돌려주는 클라이언트. 채점기가 정답을 정답으로 채점하는지 확인하는 용도로만 쓴다.
 * 운영 설정에서는 사용하지 않는다.
 */
@Component
@ConditionalOnProperty(name = "chat.llm.provider", havingValue = "oracle")
public class OracleLlmClient implements LlmClient {

    private final Map<String, GoldenItem> itemsByQuestion;
    private final ObjectMapper objectMapper;

    public OracleLlmClient(GoldenSetLoader loader, EvalProperties evalProperties, ObjectMapper objectMapper) {
        this.itemsByQuestion = loader.load(Path.of(evalProperties.goldenPath())).stream()
                .collect(Collectors.toMap(GoldenItem::question, Function.identity()));
        this.objectMapper = objectMapper;
    }

    @Override
    public LlmReply generate(List<LlmMessage> messages) {
        String question = messages.get(messages.size() - 1).content();
        GoldenItem item = itemsByQuestion.get(question);
        if (item == null) {
            throw new IllegalStateException("oracle has no golden item for question: " + question);
        }
        Map<String, String> output = new LinkedHashMap<>();
        output.put("action", item.expectedAction().value());
        output.put("sql", item.expectedAction() == Action.SQL ? item.goldenSql() : "");
        output.put("message", item.expectedAction() == Action.SQL ? "" : "oracle");
        try {
            return new LlmReply(objectMapper.writeValueAsString(output), 0, 0);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public String modelName() {
        return "oracle";
    }
}
