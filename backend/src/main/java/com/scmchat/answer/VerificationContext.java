package com.scmchat.answer;

import com.scmchat.executor.QueryResult;
import com.scmchat.tool.ToolCall;
import java.time.LocalDate;

/** 답변 문장의 숫자가 나올 수 있는 출처. toolCall은 SQL 경로면 null이다. */
public record VerificationContext(String question, QueryResult result, ToolCall toolCall, LocalDate asOfDate) {
}
