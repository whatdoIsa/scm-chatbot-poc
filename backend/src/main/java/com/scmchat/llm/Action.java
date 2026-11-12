package com.scmchat.llm;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;
import java.util.Optional;

public enum Action {
    SQL("sql"),
    CLARIFY("clarify"),
    REFUSE("refuse");

    private final String value;

    Action(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    public static Optional<Action> fromValue(String value) {
        return Arrays.stream(values()).filter(action -> action.value.equals(value)).findFirst();
    }
}
