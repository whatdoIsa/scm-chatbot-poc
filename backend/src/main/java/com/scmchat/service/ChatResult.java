package com.scmchat.service;

import com.scmchat.executor.QueryResult;
import com.scmchat.llm.Action;
import com.scmchat.llm.Route;
import com.scmchat.prompt.PromptVersion;
import java.util.Map;

/**
 * 질문 한 건의 처리 결과. action은 LLM 호출 실패나 형식 오류면 null이고,
 * result는 조회(SQL 또는 도구)에 성공했을 때만 있다.
 * route, toolName, toolArgs는 v3에서만 채워진다. toolArgs는 검증을 통과했으면 정규화된 값이고 아니면 모델이 낸 값이다.
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
        Long chatLogId,
        Route route,
        String toolName,
        Map<String, Object> toolArgs) {

    /** 라우터가 없는 v0~v2의 결과. */
    public ChatResult(Action action, ChatStatus status, String message, String sql, QueryResult result,
            PromptVersion promptVersion, String model, int attempts, int promptTokens, int completionTokens,
            int elapsedMs, Long chatLogId) {
        this(action, status, message, sql, result, promptVersion, model, attempts, promptTokens, completionTokens,
                elapsedMs, chatLogId, null, null, null);
    }
}
