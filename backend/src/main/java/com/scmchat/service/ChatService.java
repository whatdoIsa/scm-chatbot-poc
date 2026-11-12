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
import com.scmchat.validator.SqlValidator;
import com.scmchat.validator.ValidationResult;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 질문을 프롬프트로 조립해 LLM에 보내고, 응답의 action에 따라 SQL을 실행하거나 문장을 돌려준다.
 * LLM 호출 실패, 형식 오류, SQL 실행 오류는 예외로 내보내지 않고 status로 바꾼다. 모든 요청은 chat_log에 한 행으로 남는다.
 * v2는 실행 전에 SQL을 검증하고, 실행 오류가 나면 오류 문구를 붙여 한 번만 다시 묻는다. 검증에서 거부된 SQL은 다시 묻지 않는다.
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);
    private static final String LLM_UNAVAILABLE_MESSAGE = "LLM server is unavailable";

    private final PromptBuilder promptBuilder;
    private final LlmClient llmClient;
    private final LlmOutputParser parser;
    private final SqlValidator validator;
    private final ReadOnlySqlExecutor executor;
    private final ChatLogRepository chatLogRepository;

    public ChatService(PromptBuilder promptBuilder, LlmClient llmClient, LlmOutputParser parser,
            SqlValidator validator, ReadOnlySqlExecutor executor, ChatLogRepository chatLogRepository) {
        this.promptBuilder = promptBuilder;
        this.llmClient = llmClient;
        this.parser = parser;
        this.validator = validator;
        this.executor = executor;
        this.chatLogRepository = chatLogRepository;
    }

    public ChatResult answer(String question, PromptVersion version) {
        long startedAt = System.nanoTime();
        List<LlmMessage> messages = promptBuilder.build(version, question);
        int maxAttempts = version == PromptVersion.V2 ? 2 : 1;
        int attempts = 0;
        int promptTokens = 0;
        int completionTokens = 0;
        Outcome outcome;

        while (true) {
            attempts++;
            LlmReply reply;
            try {
                reply = llmClient.generate(messages);
            } catch (LlmUnavailableException e) {
                log.warn("LLM call failed", e);
                outcome = new Outcome(null, ChatStatus.LLM_UNAVAILABLE, LLM_UNAVAILABLE_MESSAGE, null, null, null);
                break;
            }
            promptTokens += reply.promptTokens();
            completionTokens += reply.completionTokens();

            outcome = interpret(reply, version);
            if (outcome.retryError() == null || attempts >= maxAttempts) {
                break;
            }
            messages = promptBuilder.buildRetry(messages, reply.rawContent(), outcome.retryError());
        }
        return record(question, version, startedAt, attempts, promptTokens, completionTokens, outcome);
    }

    private Outcome interpret(LlmReply reply, PromptVersion version) {
        ParsedOutput parsed;
        try {
            parsed = parser.parse(reply.rawContent());
        } catch (FormatException e) {
            return new Outcome(null, ChatStatus.FORMAT_ERROR, e.getMessage(), null, null, null);
        }
        if (parsed.action() != Action.SQL) {
            return new Outcome(parsed.action(), ChatStatus.OK, parsed.message(), null, null, null);
        }
        String sql = parsed.sql();
        if (version == PromptVersion.V2) {
            ValidationResult validation = validator.validate(sql);
            if (!validation.ok()) {
                return new Outcome(Action.SQL, ChatStatus.SQL_REJECTED, validation.reason(), sql, null, null);
            }
            sql = validation.sql();
        }
        try {
            QueryResult result = executor.execute(sql);
            return new Outcome(Action.SQL, ChatStatus.OK, null, sql, result, null);
        } catch (SqlExecutionException e) {
            return new Outcome(Action.SQL, ChatStatus.SQL_EXECUTION_ERROR, e.getMessage(), sql, null, e.getMessage());
        }
    }

    private ChatResult record(String question, PromptVersion version, long startedAt, int attempts,
            int promptTokens, int completionTokens, Outcome outcome) {
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

    /** retryError가 있으면 실행 오류라서 v2가 한 번 더 물어볼 수 있다. */
    private record Outcome(Action action, ChatStatus status, String message, String sql, QueryResult result,
            String retryError) {
    }
}
