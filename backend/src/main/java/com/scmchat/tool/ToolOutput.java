package com.scmchat.tool;

import java.util.Arrays;
import java.util.Optional;

/** 조회 도구가 결과를 돌려주는 방식(PRD 10.4). */
public enum ToolOutput {
    VALUE("value"),
    RANKING("ranking"),
    LIST("list"),
    COUNT("count");

    private final String value;

    ToolOutput(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static Optional<ToolOutput> fromValue(String value) {
        return Arrays.stream(values()).filter(output -> output.value.equals(value)).findFirst();
    }
}
