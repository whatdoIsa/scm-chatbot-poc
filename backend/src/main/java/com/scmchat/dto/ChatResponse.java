package com.scmchat.dto;

import com.scmchat.executor.QueryResult;
import com.scmchat.llm.Action;
import com.scmchat.service.ChatResult;
import com.scmchat.service.ChatStatus;
import java.time.LocalDate;
import java.util.List;

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
        Long chatLogId) {

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
                result.chatLogId());
    }
}
