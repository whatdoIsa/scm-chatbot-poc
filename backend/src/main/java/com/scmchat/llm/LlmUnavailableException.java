package com.scmchat.llm;

/** LLM 서버에 연결하지 못했거나 응답을 받지 못했을 때 던진다. */
public class LlmUnavailableException extends RuntimeException {

    public LlmUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
