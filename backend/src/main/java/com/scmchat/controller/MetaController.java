package com.scmchat.controller;

import com.scmchat.config.ChatProperties;
import com.scmchat.dto.MetaResponse;
import com.scmchat.llm.LlmClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class MetaController {

    private final ChatProperties chatProperties;
    private final LlmClient llmClient;

    public MetaController(ChatProperties chatProperties, LlmClient llmClient) {
        this.chatProperties = chatProperties;
        this.llmClient = llmClient;
    }

    @GetMapping("/meta")
    public MetaResponse meta() {
        return new MetaResponse(
                chatProperties.asOfDate(),
                chatProperties.promptVersion(),
                llmClient.modelName(),
                chatProperties.llm().provider());
    }
}
