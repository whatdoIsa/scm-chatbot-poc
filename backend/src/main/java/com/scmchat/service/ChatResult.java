package com.scmchat.service;

import com.scmchat.executor.QueryResult;
import com.scmchat.llm.Action;
import com.scmchat.prompt.PromptVersion;

/**
 * 질문 한 건의 처리 결과. action은 LLM 호출 실패나 형식 오류면 null이고,
 * result는 SQL 실행에 성공했을 때만 있다.
 */
public record ChatResult(
        Action action,
        ChatStatus status,
        String message,
        String sql,
        QueryResult result,
        PromptVersion promptVersion,
        String model,
        int attempts,
        int promptTokens,
        int completionTokens,
        int elapsedMs,
        Long chatLogId) {
}
