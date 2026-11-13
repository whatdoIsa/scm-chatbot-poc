package com.scmchat.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.scmchat.config.ChatProperties;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class OllamaLlmClientTest {

    private static final List<LlmMessage> MESSAGES =
            List.of(LlmMessage.system("system prompt"), LlmMessage.user("SKU-007의 현재 재고는?"));

    private MockRestServiceServer server;
    private OllamaLlmClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://localhost:11434");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new OllamaLlmClient(builder.build(),
                new ChatProperties.Llm("ollama", "http://localhost:11434", "qwen2.5-coder:7b", 0, 42, 8192, 120));
    }

    @Test
    void sendsRequestInApiSpecFormat() {
        server.expect(requestTo("http://localhost:11434/api/chat"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {
                          "model": "qwen2.5-coder:7b",
                          "messages": [
                            {"role": "system", "content": "system prompt"},
                            {"role": "user", "content": "SKU-007의 현재 재고는?"}
                          ],
                          "stream": false,
                          "keep_alive": "30m",
                          "format": {
                            "type": "object",
                            "properties": {
                              "action": {"type": "string", "enum": ["sql", "clarify", "refuse"]},
                              "sql": {"type": "string"},
                              "message": {"type": "string"}
                            },
                            "required": ["action", "sql", "message"]
                          },
                          "options": {"temperature": 0, "seed": 42, "num_ctx": 8192}
                        }
                        """, JsonCompareMode.STRICT))
                .andRespond(withSuccess("{\"message\":{\"role\":\"assistant\",\"content\":\"{}\"}}",
                        MediaType.APPLICATION_JSON));

        client.generate(MESSAGES, OutputFormat.ACTION);

        server.verify();
    }

    /** v0~v2 측정 때 모델이 받은 본문과 한 글자도 다르지 않아야 한다(키 순서 포함). */
    @Test
    void actionRequestBodyIsByteForByteTheMeasuredOne() {
        server.expect(requestTo("http://localhost:11434/api/chat"))
                .andExpect(content().string("{\"model\":\"qwen2.5-coder:7b\","
                        + "\"messages\":[{\"role\":\"system\",\"content\":\"system prompt\"},"
                        + "{\"role\":\"user\",\"content\":\"SKU-007의 현재 재고는?\"}],"
                        + "\"stream\":false,\"keep_alive\":\"30m\","
                        + "\"format\":{\"type\":\"object\",\"properties\":{"
                        + "\"action\":{\"type\":\"string\",\"enum\":[\"sql\",\"clarify\",\"refuse\"]},"
                        + "\"sql\":{\"type\":\"string\"},\"message\":{\"type\":\"string\"}},"
                        + "\"required\":[\"action\",\"sql\",\"message\"]},"
                        + "\"options\":{\"temperature\":0.0,\"seed\":42,\"num_ctx\":8192}}"))
                .andRespond(withSuccess("{\"message\":{\"role\":\"assistant\",\"content\":\"{}\"}}",
                        MediaType.APPLICATION_JSON));

        client.generate(MESSAGES, OutputFormat.ACTION);

        server.verify();
    }

    @Test
    void sendsTheRouteSchemaForRouterCalls() {
        server.expect(requestTo("http://localhost:11434/api/chat"))
                .andExpect(content().json("""
                        {
                          "model": "qwen2.5-coder:7b",
                          "messages": [
                            {"role": "system", "content": "system prompt"},
                            {"role": "user", "content": "SKU-007의 현재 재고는?"}
                          ],
                          "stream": false,
                          "keep_alive": "30m",
                          "format": {
                            "type": "object",
                            "properties": {
                              "route": {"type": "string", "enum": ["tool", "sql", "clarify", "refuse"]},
                              "tool": {"type": "string", "enum": ["", "avg_daily_sales", "days_of_supply",
                                "stockout_rate", "forecast_accuracy", "below_safety_stock", "reorder_needed"]},
                              "sku": {"type": "string"},
                              "location": {"type": "string"},
                              "days": {"type": "integer"},
                              "start_date": {"type": "string"},
                              "end_date": {"type": "string"},
                              "output": {"type": "string", "enum": ["", "value", "ranking", "list", "count"]},
                              "top_n": {"type": "integer"},
                              "order": {"type": "string", "enum": ["", "asc", "desc"]},
                              "message": {"type": "string"}
                            },
                            "required": ["route", "tool", "sku", "location", "days", "start_date", "end_date",
                              "output", "top_n", "order", "message"]
                          },
                          "options": {"temperature": 0, "seed": 42, "num_ctx": 8192}
                        }
                        """, JsonCompareMode.STRICT))
                .andRespond(withSuccess("{\"message\":{\"role\":\"assistant\",\"content\":\"{}\"}}",
                        MediaType.APPLICATION_JSON));

        client.generate(MESSAGES, OutputFormat.ROUTE);

        server.verify();
    }

    /** v3은 이 스키마로 측정했다. v3-freeze 태그에서 직렬화한 값과 같아야 한다(ROUTE_V4를 더해도 바뀌지 않음). */
    @Test
    void v3RouteSchemaIsTheOneMeasuredAtV3Freeze() throws Exception {
        assertThat(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(OutputFormat.ROUTE.schema()))
                .isEqualTo("{\"type\":\"object\",\"properties\":{\"route\":{\"type\":\"string\",\"enum\":"
                        + "[\"tool\",\"sql\",\"clarify\",\"refuse\"]},\"tool\":{\"type\":\"string\",\"enum\":[\"\","
                        + "\"avg_daily_sales\",\"days_of_supply\",\"stockout_rate\",\"forecast_accuracy\","
                        + "\"below_safety_stock\",\"reorder_needed\"]},\"sku\":{\"type\":\"string\"},\"location\":"
                        + "{\"type\":\"string\"},\"days\":{\"type\":\"integer\"},\"start_date\":{\"type\":\"string\"},"
                        + "\"end_date\":{\"type\":\"string\"},\"output\":{\"type\":\"string\",\"enum\":[\"\",\"value\","
                        + "\"ranking\",\"list\",\"count\"]},\"top_n\":{\"type\":\"integer\"},\"order\":{\"type\":"
                        + "\"string\",\"enum\":[\"\",\"asc\",\"desc\"]},\"message\":{\"type\":\"string\"}},"
                        + "\"required\":[\"route\",\"tool\",\"sku\",\"location\",\"days\",\"start_date\",\"end_date\","
                        + "\"output\",\"top_n\",\"order\",\"message\"]}");
    }

    /** ROUTE_V4는 ROUTE의 location 다음에 조건 인자 세 개를 끼운 것이다(API_SPEC 9.1). */
    @Test
    void v4RouteSchemaInsertsTheConditionArgumentsAfterLocation() {
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> v4 = (java.util.Map<String, Object>) OutputFormat.ROUTE_V4.schema().get("properties");
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> v3 = (java.util.Map<String, Object>) OutputFormat.ROUTE.schema().get("properties");

        assertThat(v4.keySet()).containsExactly("route", "tool", "sku", "location", "region", "category", "abc_class",
                "days", "start_date", "end_date", "output", "top_n", "order", "message");
        assertThat(OutputFormat.ROUTE_V4.schema().get("required")).isEqualTo(List.copyOf(v4.keySet()));
        assertThat(v4.get("abc_class")).isEqualTo(java.util.Map.of("type", "string", "enum", List.of("", "A", "B", "C")));
        v3.forEach((key, value) -> assertThat(v4.get(key)).as(key).isEqualTo(value));
        assertThat(OutputFormat.ROUTE_V4.isRoute()).isTrue();
        assertThat(OutputFormat.ACTION.isRoute()).isFalse();
    }

    @Test
    void routeSchemaListsEveryServerTool() {
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> properties =
                (java.util.Map<String, Object>) OutputFormat.ROUTE.schema().get("properties");
        @SuppressWarnings("unchecked")
        List<String> tools = (List<String>) ((java.util.Map<String, Object>) properties.get("tool")).get("enum");

        assertThat(tools).containsExactlyInAnyOrderElementsOf(java.util.stream.Stream.concat(
                java.util.stream.Stream.of(""),
                java.util.Arrays.stream(com.scmchat.tool.ToolName.values()).map(com.scmchat.tool.ToolName::value))
                .toList());
    }

    @Test
    void mapsContentAndTokenCounts() {
        server.expect(requestTo("http://localhost:11434/api/chat")).andRespond(withSuccess("""
                {"model": "qwen2.5-coder:7b", "done": true,
                 "message": {"role": "assistant", "content": "{\\"action\\":\\"sql\\"}"},
                 "prompt_eval_count": 512, "eval_count": 37, "total_duration": 123}
                """, MediaType.APPLICATION_JSON));

        assertThat(client.generate(MESSAGES, OutputFormat.ACTION)).isEqualTo(new LlmReply("{\"action\":\"sql\"}", 512, 37));
    }

    @Test
    void usesZeroWhenTokenCountsAreMissing() {
        server.expect(requestTo("http://localhost:11434/api/chat")).andRespond(withSuccess(
                "{\"message\":{\"role\":\"assistant\",\"content\":\"x\"}}", MediaType.APPLICATION_JSON));

        assertThat(client.generate(MESSAGES, OutputFormat.ACTION)).isEqualTo(new LlmReply("x", 0, 0));
    }

    @Test
    void returnsEmptyContentWhenMessageIsMissing() {
        server.expect(requestTo("http://localhost:11434/api/chat"))
                .andRespond(withSuccess("{\"done\":true}", MediaType.APPLICATION_JSON));

        assertThat(client.generate(MESSAGES, OutputFormat.ACTION)).isEqualTo(new LlmReply("", 0, 0));
    }

    @Test
    void wrapsServerErrorAsUnavailable() {
        server.expect(requestTo("http://localhost:11434/api/chat")).andRespond(withServerError());

        assertThatThrownBy(() -> client.generate(MESSAGES, OutputFormat.ACTION)).isInstanceOf(LlmUnavailableException.class);
    }

    @Test
    void wrapsConnectionFailureAsUnavailable() {
        server.expect(requestTo("http://localhost:11434/api/chat")).andRespond(request -> {
            throw new IOException("Connection refused");
        });

        assertThatThrownBy(() -> client.generate(MESSAGES, OutputFormat.ACTION))
                .isInstanceOf(LlmUnavailableException.class)
                .hasMessageContaining("Connection refused");
    }

    @Test
    void reportsConfiguredModelName() {
        assertThat(client.modelName()).isEqualTo("qwen2.5-coder:7b");
    }
}
