package com.scmchat.llm;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;
import java.util.Optional;

/** v3 라우터가 고르는 경로(PRD 10.2). */
public enum Route {
    TOOL("tool"),
    SQL("sql"),
    CLARIFY("clarify"),
    REFUSE("refuse");

    private final String value;

    Route(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    public static Optional<Route> fromValue(String value) {
        return Arrays.stream(values()).filter(route -> route.value.equals(value)).findFirst();
    }
}
