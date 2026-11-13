package com.scmchat.prompt;

import java.util.Arrays;

public enum PromptVersion {
    V0("v0"),
    V1("v1"),
    V2("v2"),
    /** 라우터, 조회 도구, SQL 단계로 나뉜다. 단일 프롬프트 파일이 없고 SQL 단계는 v1 프롬프트를 쓴다. */
    V3("v3"),
    /** v3과 같은 흐름에 라우터 프롬프트, 도구 목록, 라우터 출력 형식만 다르다(PRD 11장). */
    V4("v4"),
    /** v4의 라우터 프롬프트와 출력 형식을 그대로 쓰고, 서버가 도구 호출을 질문과 대조해 맞지 않으면 SQL 단계로 보낸다(PRD 12장). */
    V5("v5");

    private final String value;

    PromptVersion(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    /** 라우터가 경로를 먼저 고르는 버전. */
    public boolean routed() {
        return this == V3 || this == V4 || this == V5;
    }

    public static PromptVersion fromValue(String value) {
        return Arrays.stream(values())
                .filter(version -> version.value.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown prompt version: " + value));
    }
}
