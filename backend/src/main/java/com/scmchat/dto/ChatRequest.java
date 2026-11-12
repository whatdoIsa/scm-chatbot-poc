package com.scmchat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChatRequest(
        @NotBlank(message = ChatRequest.QUESTION_RULE)
        @Size(max = 500, message = ChatRequest.QUESTION_RULE)
        String question) {

    static final String QUESTION_RULE = "question must be 1..500 characters";

    // 길이 규칙은 앞뒤 공백을 뺀 질문에 적용한다.
    public ChatRequest {
        question = question == null ? null : question.strip();
    }
}
