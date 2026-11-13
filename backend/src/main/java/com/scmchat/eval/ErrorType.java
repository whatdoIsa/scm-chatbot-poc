package com.scmchat.eval;

/**
 * 오답 원인(PRD 5.4, 10.6). 자동으로 기록하는 것은 ACTION_MISMATCH, FORMAT_ERROR, SQL_EXECUTION_ERROR와
 * 도구 인자 검증 실패(TOOL_ARGUMENT)뿐이고 나머지는 사람이 분류한다.
 */
public enum ErrorType {
    SCHEMA_MISUNDERSTANDING,
    METRIC_DEFINITION,
    DATE_RANGE,
    JOIN_AGGREGATION,
    ACTION_MISMATCH,
    FORMAT_ERROR,
    SQL_EXECUTION_ERROR,
    /** v3: 도구가 표현할 수 없는 질문을 도구로 보냈거나 다른 도구를 골랐다. */
    TOOL_SELECTION,
    /** v3: 도구는 맞고 인자가 틀렸다. */
    TOOL_ARGUMENT
}
