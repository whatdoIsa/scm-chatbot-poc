package com.scmchat.llm;

/** LLM 출력이 API_SPEC 3.2의 형식을 지키지 않았을 때 던진다. */
public class FormatException extends RuntimeException {

    public FormatException(String message) {
        super(message);
    }

    public FormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
