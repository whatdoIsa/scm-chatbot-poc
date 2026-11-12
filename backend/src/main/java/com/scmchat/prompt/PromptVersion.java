package com.scmchat.prompt;

import java.util.Arrays;

public enum PromptVersion {
    V0("v0"),
    V1("v1"),
    V2("v2");

    private final String value;

    PromptVersion(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static PromptVersion fromValue(String value) {
        return Arrays.stream(values())
                .filter(version -> version.value.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown prompt version: " + value));
    }
}
