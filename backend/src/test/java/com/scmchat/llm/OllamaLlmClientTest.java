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

        client.generate(MESSAGES);

        server.verify();
    }

    @Test
    void mapsContentAndTokenCounts() {
        server.expect(requestTo("http://localhost:11434/api/chat")).andRespond(withSuccess("""
                {"model": "qwen2.5-coder:7b", "done": true,
                 "message": {"role": "assistant", "content": "{\\"action\\":\\"sql\\"}"},
                 "prompt_eval_count": 512, "eval_count": 37, "total_duration": 123}
                """, MediaType.APPLICATION_JSON));

        assertThat(client.generate(MESSAGES)).isEqualTo(new LlmReply("{\"action\":\"sql\"}", 512, 37));
    }

    @Test
    void usesZeroWhenTokenCountsAreMissing() {
        server.expect(requestTo("http://localhost:11434/api/chat")).andRespond(withSuccess(
                "{\"message\":{\"role\":\"assistant\",\"content\":\"x\"}}", MediaType.APPLICATION_JSON));

        assertThat(client.generate(MESSAGES)).isEqualTo(new LlmReply("x", 0, 0));
    }

    @Test
    void returnsEmptyContentWhenMessageIsMissing() {
        server.expect(requestTo("http://localhost:11434/api/chat"))
                .andRespond(withSuccess("{\"done\":true}", MediaType.APPLICATION_JSON));

        assertThat(client.generate(MESSAGES)).isEqualTo(new LlmReply("", 0, 0));
    }

    @Test
    void wrapsServerErrorAsUnavailable() {
        server.expect(requestTo("http://localhost:11434/api/chat")).andRespond(withServerError());

        assertThatThrownBy(() -> client.generate(MESSAGES)).isInstanceOf(LlmUnavailableException.class);
    }

    @Test
    void wrapsConnectionFailureAsUnavailable() {
        server.expect(requestTo("http://localhost:11434/api/chat")).andRespond(request -> {
            throw new IOException("Connection refused");
        });

        assertThatThrownBy(() -> client.generate(MESSAGES))
                .isInstanceOf(LlmUnavailableException.class)
                .hasMessageContaining("Connection refused");
    }

    @Test
    void reportsConfiguredModelName() {
        assertThat(client.modelName()).isEqualTo("qwen2.5-coder:7b");
    }
}
