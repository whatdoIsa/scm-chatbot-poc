package com.scmchat.controller;

import com.scmchat.config.ChatProperties;
import com.scmchat.dto.ChatRequest;
import com.scmchat.dto.ChatResponse;
import com.scmchat.prompt.PromptVersion;
import com.scmchat.service.ChatResult;
import com.scmchat.service.ChatService;
import com.scmchat.service.ChatStatus;
import jakarta.validation.Valid;
import java.time.LocalDate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class ChatController {

    private final ChatService chatService;
    private final PromptVersion promptVersion;
    private final LocalDate asOfDate;

    public ChatController(ChatService chatService, ChatProperties chatProperties) {
        this.chatService = chatService;
        this.promptVersion = PromptVersion.fromValue(chatProperties.promptVersion());
        this.asOfDate = chatProperties.asOfDate();
    }

    @PostMapping("/chat")
    public ResponseEntity<ChatResponse> chat(@Valid @RequestBody ChatRequest request) {
        ChatResult result = chatService.answer(request.question().strip(), promptVersion);
        return ResponseEntity.status(httpStatusOf(result.status())).body(ChatResponse.from(result, asOfDate));
    }

    private static HttpStatus httpStatusOf(ChatStatus status) {
        return switch (status) {
            case OK -> HttpStatus.OK;
            case SQL_REJECTED, SQL_EXECUTION_ERROR, FORMAT_ERROR -> HttpStatus.UNPROCESSABLE_ENTITY;
            case LLM_UNAVAILABLE -> HttpStatus.BAD_GATEWAY;
        };
    }
}
