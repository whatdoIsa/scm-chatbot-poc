package com.scmchat.service;

import com.scmchat.entity.ChatLog;
import com.scmchat.executor.QueryResult;
import com.scmchat.executor.ReadOnlySqlExecutor;
import com.scmchat.executor.SqlExecutionException;
import com.scmchat.llm.Action;
import com.scmchat.llm.FormatException;
import com.scmchat.llm.LlmClient;
import com.scmchat.llm.LlmMessage;
import com.scmchat.llm.LlmOutputParser;
import com.scmchat.llm.LlmReply;
import com.scmchat.llm.LlmUnavailableException;
import com.scmchat.llm.ParsedOutput;
import com.scmchat.prompt.PromptBuilder;
import com.scmchat.prompt.PromptVersion;
import com.scmchat.repository.ChatLogRepository;
import java.time.Duration;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 질문을 프롬프트로 조립해 LLM에 보내고, 응답의 action에 따라 SQL을 실행하거나 문장을 돌려준다.
 * LLM 호출 실패, 형식 오류, SQL 실행 오류는 예외로 내보내지 않고 status로 바꾼다. 모든 요청은 chat_log에 한 행으로 남는다.
 */
@Service
public class ChatService {

    private final PromptBuilder promptBuilder;
    private final LlmClient llmClient;
    private final LlmOutputParser parser;
    private final ReadOnlySqlExecutor executor;
    private final ChatLogRepository chatLogRepository;

    public ChatService(PromptBuilder promptBuilder, LlmClient llmClient, LlmOutputParser parser,
            ReadOnlySqlExecutor executor, ChatLogRepository chatLogRepository) {
        this.promptBuilder = promptBuilder;
        this.llmClient = llmClient;
        this.parser = parser;
        this.executor = executor;
        this.chatLogRepository = chatLogRepository;
    }

    public ChatResult answer(String question, PromptVersion version) {
        long startedAt = System.nanoTime();
        List<LlmMessage> messages = promptBuilder.build(version, question);

        LlmReply reply;
        try {
            reply = llmClient.generate(messages);
        } catch (LlmUnavailableException e) {
            return record(question, version, startedAt, null,
                    new Outcome(null, ChatStatus.LLM_UNAVAILABLE, e.getMessage(), null, null));
        }
        return record(question, version, startedAt, reply, interpret(reply));
    }

    private Outcome interpret(LlmReply reply) {
        ParsedOutput parsed;
        try {
            parsed = parser.parse(reply.rawContent());
        } catch (FormatException e) {
            return new Outcome(null, ChatStatus.FORMAT_ERROR, e.getMessage(), null, null);
        }
        if (parsed.action() != Action.SQL) {
            return new Outcome(parsed.action(), ChatStatus.OK, parsed.message(), null, null);
        }
        try {
            QueryResult result = executor.execute(parsed.sql());
            return new Outcome(Action.SQL, ChatStatus.OK, null, parsed.sql(), result);
        } catch (SqlExecutionException e) {
            return new Outcome(Action.SQL, ChatStatus.SQL_EXECUTION_ERROR, e.getMessage(), parsed.sql(), null);
        }
    }

    private ChatResult record(String question, PromptVersion version, long startedAt, LlmReply reply,
            Outcome outcome) {
        int promptTokens = reply == null ? 0 : reply.promptTokens();
        int completionTokens = reply == null ? 0 : reply.completionTokens();
        int attempts = 1;
        int elapsedMs = (int) Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
        Integer rowCount = outcome.result() == null ? null : outcome.result().rows().size();

        ChatLog saved = chatLogRepository.save(new ChatLog(
                question,
                version.value(),
                llmClient.modelName(),
                outcome.action() == null ? null : outcome.action().value(),
                outcome.status().name(),
                outcome.sql(),
                rowCount,
                attempts,
                promptTokens,
                completionTokens,
                elapsedMs,
                outcome.status() == ChatStatus.OK ? null : outcome.message()));

        return new ChatResult(outcome.action(), outcome.status(), outcome.message(), outcome.sql(),
                outcome.result(), version, llmClient.modelName(), attempts, promptTokens, completionTokens,
                elapsedMs, saved.getId());
    }

    private record Outcome(Action action, ChatStatus status, String message, String sql, QueryResult result) {
    }
}
