package com.scmchat.answer;

/** v3 답변 문장의 처리 결과(API_SPEC 8.1). */
public enum AnswerStatus {
    /** 문장의 숫자가 모두 허용 집합에 있다. 문장을 응답에 싣는다. */
    VERIFIED,
    /** 허용되지 않은 숫자가 있다. 문장은 로그에만 남긴다. */
    REJECTED,
    /** 문장을 만들지 않았거나 받지 못했다. */
    SKIPPED
}
