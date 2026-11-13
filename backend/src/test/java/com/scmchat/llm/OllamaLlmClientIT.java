package com.scmchat.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.scmchat.prompt.PromptBuilder;
import com.scmchat.prompt.PromptVersion;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** 실제 Ollama를 호출해 v0 프롬프트의 응답이 파싱되고 토큰 수가 들어오는지 확인한다. */
@Tag("integration")
@Tag("ollama")
@SpringBootTest(properties = "chat.llm.provider=ollama")
class OllamaLlmClientIT {

    @Autowired
    private LlmClient llmClient;

    @Autowired
    private PromptBuilder promptBuilder;

    @Autowired
    private LlmOutputParser parser;

    @Test
    void returnsParseableJsonWithTokenCounts() {
        assertThat(llmClient).isInstanceOf(OllamaLlmClient.class);

        LlmReply reply = llmClient.generate(promptBuilder.build(PromptVersion.V0, "SKU-007의 현재 재고는?"),
                OutputFormat.ACTION);
        ParsedOutput output = parser.parse(reply.rawContent());

        assertThat(output.action()).isNotNull();
        assertThat(reply.promptTokens()).isPositive();
        assertThat(reply.completionTokens()).isPositive();
    }
}
