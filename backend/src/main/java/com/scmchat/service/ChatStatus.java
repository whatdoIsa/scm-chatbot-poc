package com.scmchat.service;

public enum ChatStatus {
    OK,
    SQL_REJECTED,
    SQL_EXECUTION_ERROR,
    /** v3: 라우터가 낸 도구 호출이 인자 검증을 통과하지 못했다. */
    TOOL_REJECTED,
    FORMAT_ERROR,
    LLM_UNAVAILABLE
}
