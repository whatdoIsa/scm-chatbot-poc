package com.scmchat.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.scmchat.config.ChatProperties;
import com.scmchat.llm.FakeLlmClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(MetaController.class)
@EnableConfigurationProperties(ChatProperties.class)
@Import(FakeLlmClient.class)
class MetaControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void returnsMetaWithFakeProviderAndItsModelNameByDefault() throws Exception {
        mockMvc.perform(get("/api/meta"))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"asOfDate": "2026-11-08", "promptVersion": "v1",
                         "model": "fake", "llmProvider": "fake"}
                        """, JsonCompareMode.STRICT));
    }
}
