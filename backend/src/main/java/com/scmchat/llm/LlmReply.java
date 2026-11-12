package com.scmchat.llm;

public record LlmReply(String rawContent, int promptTokens, int completionTokens) {
}
