package com.scmchat.eval;

/** 오답 원인(PRD 5.4). 자동으로 기록하는 것은 뒤의 세 가지뿐이고 나머지는 사람이 분류한다. */
public enum ErrorType {
    SCHEMA_MISUNDERSTANDING,
    METRIC_DEFINITION,
    DATE_RANGE,
    JOIN_AGGREGATION,
    ACTION_MISMATCH,
    FORMAT_ERROR,
    SQL_EXECUTION_ERROR
}
