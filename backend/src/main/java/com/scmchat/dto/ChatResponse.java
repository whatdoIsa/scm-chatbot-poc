package com.scmchat.dto;

import com.scmchat.answer.AnswerStatus;
import com.scmchat.executor.QueryResult;
import com.scmchat.llm.Action;
import com.scmchat.llm.Route;
import com.scmchat.service.ChatResult;
import com.scmchat.service.ChatStatus;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public record ChatResponse(
        Action action,
        ChatStatus status,
        String message,
        String sql,
        List<String> columns,
        List<List<Object>> rows,
        Integer rowCount,
        boolean truncated,
        LocalDate asOfDate,
        String promptVersion,
        String model,
        int attempts,
        int elapsedMs,
        Long chatLogId,
        Route route,
        ToolInfo tool,
        String answer,
        AnswerStatus answerStatus) {

    /** v3 도구 경로의 근거(API_SPEC 8.1). */
    public record ToolInfo(String name, Map<String, Object> args) {
    }

    public static ChatResponse from(ChatResult result, LocalDate asOfDate) {
        QueryResult queryResult = result.result();
        return new ChatResponse(
                result.action(),
                result.status(),
                result.message(),
                result.sql(),
                queryResult == null ? null : queryResult.columns(),
                queryResult == null ? null : queryResult.rows(),
                queryResult == null ? null : queryResult.rows().size(),
                queryResult != null && queryResult.truncated(),
                asOfDate,
                result.promptVersion().value(),
                result.model(),
                result.attempts(),
                result.elapsedMs(),
                result.chatLogId(),
                result.route(),
                result.route() == Route.TOOL ? new ToolInfo(result.toolName(), result.toolArgs()) : null,
                // 숫자 후검증을 통과한 문장만 내보낸다.
                result.answerStatus() == AnswerStatus.VERIFIED ? result.answerText() : null,
                result.answerStatus());
    }
}
