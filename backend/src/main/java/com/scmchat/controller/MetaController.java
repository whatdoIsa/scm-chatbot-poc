package com.scmchat.controller;

import com.scmchat.config.ChatProperties;
import com.scmchat.dto.MetaResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class MetaController {

    private final ChatProperties chatProperties;

    public MetaController(ChatProperties chatProperties) {
        this.chatProperties = chatProperties;
    }

    @GetMapping("/meta")
    public MetaResponse meta() {
        return new MetaResponse(
                chatProperties.asOfDate(),
                chatProperties.promptVersion(),
                chatProperties.llm().model(),
                chatProperties.llm().provider());
    }
}
