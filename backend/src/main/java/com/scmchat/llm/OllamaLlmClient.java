package com.scmchat.llm;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.scmchat.config.ChatProperties;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** 로컬 Ollama의 /api/chat을 호출한다(API_SPEC 3.1). 출력 형식은 JSON 스키마로 강제한다. */
@Component
@ConditionalOnProperty(name = "chat.llm.provider", havingValue = "ollama")
public class OllamaLlmClient implements LlmClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final String KEEP_ALIVE = "30m";
    private static final Map<String, Object> OUTPUT_SCHEMA = outputSchema();

    private final RestClient restClient;
    private final ChatProperties.Llm llm;

    @Autowired
    public OllamaLlmClient(RestClient.Builder restClientBuilder, ChatProperties chatProperties) {
        this(restClientBuilder
                        .baseUrl(chatProperties.llm().baseUrl())
                        .requestFactory(requestFactory(chatProperties.llm()))
                        .build(),
                chatProperties.llm());
    }

    OllamaLlmClient(RestClient restClient, ChatProperties.Llm llm) {
        this.restClient = restClient;
        this.llm = llm;
    }

    @Override
    public LlmReply generate(List<LlmMessage> messages) {
        ChatRequest request = new ChatRequest(llm.model(), messages, false, KEEP_ALIVE, OUTPUT_SCHEMA,
                new Options(llm.temperature(), llm.seed(), llm.numCtx()));
        ChatResponse response;
        try {
            response = restClient.post()
                    .uri("/api/chat")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(ChatResponse.class);
        } catch (RestClientException e) {
            throw new LlmUnavailableException("Ollama call failed: " + e.getMessage(), e);
        }
        if (response == null || response.message() == null) {
            return new LlmReply("", 0, 0);
        }
        return new LlmReply(
                response.message().content() == null ? "" : response.message().content(),
                response.promptEvalCount() == null ? 0 : response.promptEvalCount(),
                response.evalCount() == null ? 0 : response.evalCount());
    }

    @Override
    public String modelName() {
        return llm.model();
    }

    // 키 순서가 실행마다 바뀌면 모델에 전달되는 스키마가 달라지므로 순서가 고정된 맵을 쓴다.
    private static Map<String, Object> outputSchema() {
        Map<String, Object> action = new LinkedHashMap<>();
        action.put("type", "string");
        action.put("enum", List.of("sql", "clarify", "refuse"));

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("action", action);
        properties.put("sql", Map.of("type", "string"));
        properties.put("message", Map.of("type", "string"));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("action", "sql", "message"));
        return schema;
    }

    private static SimpleClientHttpRequestFactory requestFactory(ChatProperties.Llm llm) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT);
        factory.setReadTimeout(Duration.ofSeconds(llm.timeoutSeconds()));
        return factory;
    }

    private record ChatRequest(
            String model,
            List<LlmMessage> messages,
            boolean stream,
            @JsonProperty("keep_alive") String keepAlive,
            Map<String, Object> format,
            Options options) {
    }

    private record Options(double temperature, int seed, @JsonProperty("num_ctx") int numCtx) {
    }

    private record ChatResponse(
            LlmMessage message,
            @JsonProperty("prompt_eval_count") Integer promptEvalCount,
            @JsonProperty("eval_count") Integer evalCount) {
    }
}
