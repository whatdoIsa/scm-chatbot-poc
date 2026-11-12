package com.scmchat.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.scmchat.config.ChatProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(MetaController.class)
@EnableConfigurationProperties(ChatProperties.class)
class MetaControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void returnsConfiguredMetaWithFakeProviderByDefault() throws Exception {
        mockMvc.perform(get("/api/meta"))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"asOfDate": "2026-11-08", "promptVersion": "v1",
                         "model": "qwen2.5-coder:7b", "llmProvider": "fake"}
                        """, JsonCompareMode.STRICT));
    }
}
