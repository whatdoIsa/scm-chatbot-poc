package com.scmchat.prompt;

import java.util.Arrays;

public enum PromptVersion {
    V0("v0"),
    V1("v1"),
    V2("v2"),
    /** 라우터, 조회 도구, SQL 단계로 나뉜다. 단일 프롬프트 파일이 없고 SQL 단계는 v1 프롬프트를 쓴다. */
    V3("v3");

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
