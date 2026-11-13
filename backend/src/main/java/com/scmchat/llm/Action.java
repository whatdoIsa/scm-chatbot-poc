package com.scmchat.llm;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;
import java.util.Optional;

public enum Action {
    SQL("sql"),
    CLARIFY("clarify"),
    REFUSE("refuse"),
    /** v3에서 조회 도구로 답했을 때. 모델이 ACTION 형식으로 낼 수 있는 값은 아니다. */
    TOOL("tool");

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
