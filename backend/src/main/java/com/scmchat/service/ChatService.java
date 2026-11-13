package com.scmchat.service;

import com.scmchat.config.ChatProperties;
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
import com.scmchat.llm.OutputFormat;
import com.scmchat.llm.ParsedOutput;
import com.scmchat.llm.ParsedRoute;
import com.scmchat.llm.Route;
import com.scmchat.prompt.PromptBuilder;
import com.scmchat.prompt.PromptVersion;
import com.scmchat.repository.ChatLogRepository;
import com.scmchat.tool.ToolArgsJson;
import com.scmchat.tool.ToolArgsValidator;
import com.scmchat.tool.ToolCall;
import com.scmchat.tool.ToolExecutor;
import com.scmchat.tool.ToolValidation;
import com.scmchat.validator.SqlValidator;
import com.scmchat.validator.ValidationResult;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 질문을 프롬프트로 조립해 LLM에 보내고, 응답에 따라 SQL이나 조회 도구를 실행하거나 문장을 돌려준다.
 * LLM 호출 실패, 형식 오류, 실행 오류는 예외로 내보내지 않고 status로 바꾼다. 모든 요청은 chat_log에 한 행으로 남는다.
 * v2는 실행 전에 SQL을 검증하고, 실행 오류가 나면 오류 문구를 붙여 한 번만 다시 묻는다. 검증에서 거부된 SQL은 다시 묻지 않는다.
 * v3은 라우터가 경로를 먼저 고른다(PRD 10.2). 도구 경로는 인자를 검증한 뒤 고정 쿼리를 실행하고,
 * SQL 경로는 v1 프롬프트에 v2의 검증과 재시도를 적용한다.
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
    private final ToolArgsValidator toolArgsValidator;
    private final ToolExecutor toolExecutor;
    private final ChatLogRepository chatLogRepository;
    private final int validatorLimit;

    public ChatService(PromptBuilder promptBuilder, LlmClient llmClient, LlmOutputParser parser,
            SqlValidator validator, ReadOnlySqlExecutor executor, ToolArgsValidator toolArgsValidator,
            ToolExecutor toolExecutor, ChatLogRepository chatLogRepository, ChatProperties chatProperties) {
        this.promptBuilder = promptBuilder;
        this.llmClient = llmClient;
        this.parser = parser;
        this.validator = validator;
        this.executor = executor;
        this.toolArgsValidator = toolArgsValidator;
        this.toolExecutor = toolExecutor;
        this.chatLogRepository = chatLogRepository;
        this.validatorLimit = chatProperties.validatorLimit();
    }

    public ChatResult answer(String question, PromptVersion version) {
        long startedAt = System.nanoTime();
        Usage usage = new Usage();
        Routed routed;
        if (version == PromptVersion.V3) {
            routed = routeAndAnswer(question, usage);
        } else {
            boolean v2 = version == PromptVersion.V2;
            routed = Routed.unrouted(sqlStage(promptBuilder.build(version, question), v2, v2 ? 2 : 1, usage));
        }
        return record(question, version, startedAt, usage, routed);
    }

    private Routed routeAndAnswer(String question, Usage usage) {
        LlmReply reply;
        try {
            reply = call(promptBuilder.buildRouter(question), OutputFormat.ROUTE, usage);
        } catch (LlmUnavailableException e) {
            log.warn("LLM call failed", e);
            return Routed.unrouted(Outcome.unavailable());
        }
        ParsedRoute parsed;
        try {
            parsed = parser.parseRoute(reply.rawContent());
        } catch (FormatException e) {
            return Routed.unrouted(Outcome.failed(null, ChatStatus.FORMAT_ERROR, e.getMessage()));
        }
        return switch (parsed.route()) {
            case CLARIFY -> new Routed(Route.CLARIFY, null, null, Outcome.message(Action.CLARIFY, parsed.message()));
            case REFUSE -> new Routed(Route.REFUSE, null, null, Outcome.message(Action.REFUSE, parsed.message()));
            // SQL 단계가 clarify나 refuse를 내면 그 action이 최종 결과다.
            case SQL -> new Routed(Route.SQL, null, null,
                    sqlStage(promptBuilder.build(PromptVersion.V1, question), true, 2, usage));
            case TOOL -> runTool(parsed);
        };
    }

    /** 인자 검증에 실패하면 SQL 경로로 넘기거나 다시 묻지 않는다. 라우터의 인자 오류를 그대로 드러낸다. */
    private Routed runTool(ParsedRoute parsed) {
        ToolValidation validation = toolArgsValidator.validate(parsed.tool(), parsed.args());
        if (!validation.ok()) {
            return new Routed(Route.TOOL, parsed.tool(), parsed.args().toArgsMap(),
                    Outcome.failed(Action.TOOL, ChatStatus.TOOL_REJECTED, validation.reason()));
        }
        ToolCall call = validation.call();
        Outcome outcome;
        try {
            outcome = new Outcome(Action.TOOL, ChatStatus.OK, null, null, toolExecutor.execute(call), null);
        } catch (SqlExecutionException e) {
            outcome = Outcome.failed(Action.TOOL, ChatStatus.SQL_EXECUTION_ERROR, e.getMessage());
        }
        return new Routed(Route.TOOL, call.tool().value(), call.toArgsMap(), outcome);
    }

    /** SQL을 받아 실행한다. 실행 오류가 나면 maxAttempts까지 오류 문구를 붙여 다시 묻는다. */
    private Outcome sqlStage(List<LlmMessage> messages, boolean validate, int maxAttempts, Usage usage) {
        Outcome firstError = null;
        int stageAttempts = 0;
        while (true) {
            stageAttempts++;
            LlmReply reply;
            try {
                reply = call(messages, OutputFormat.ACTION, usage);
            } catch (LlmUnavailableException e) {
                log.warn("LLM call failed", e);
                return firstError != null ? firstError : Outcome.unavailable();
            }

            Outcome current = interpret(reply, validate);
            // 재시도에서 SQL이 아닌 답이 오면 첫 실행 오류를 결과로 남긴다. 실행 오류가 action 불일치로 바뀌어 기록되지 않게 한다.
            Outcome outcome = firstError != null && current.action() != Action.SQL ? firstError : current;
            if (current.retryError() == null || stageAttempts >= maxAttempts) {
                return outcome;
            }
            firstError = current;
            messages = promptBuilder.buildRetry(messages, reply.rawContent(), current.retryError());
        }
    }

    private LlmReply call(List<LlmMessage> messages, OutputFormat format, Usage usage) {
        usage.attempts++;
        LlmReply reply = llmClient.generate(messages, format);
        usage.promptTokens += reply.promptTokens();
        usage.completionTokens += reply.completionTokens();
        return reply;
    }

    private Outcome interpret(LlmReply reply, boolean validate) {
        ParsedOutput parsed;
        try {
            parsed = parser.parse(reply.rawContent());
        } catch (FormatException e) {
            return Outcome.failed(null, ChatStatus.FORMAT_ERROR, e.getMessage());
        }
        if (parsed.action() != Action.SQL) {
            return Outcome.message(parsed.action(), parsed.message());
        }
        String sql = parsed.sql();
        boolean limitApplied = false;
        if (validate) {
            ValidationResult validation = validator.validate(sql);
            if (!validation.ok()) {
                return new Outcome(Action.SQL, ChatStatus.SQL_REJECTED, validation.reason(), sql, null, null);
            }
            sql = validation.sql();
            limitApplied = validation.limitApplied();
        }
        try {
            QueryResult result = executor.execute(sql);
            // 검증기가 붙인 LIMIT만큼 꽉 찬 결과는 더 있었을 수 있으므로 잘린 것으로 표시한다.
            if (limitApplied && result.rows().size() >= validatorLimit) {
                result = new QueryResult(result.columns(), result.rows(), true);
            }
            return new Outcome(Action.SQL, ChatStatus.OK, null, sql, result, null);
        } catch (SqlExecutionException e) {
            return new Outcome(Action.SQL, ChatStatus.SQL_EXECUTION_ERROR, e.getMessage(), sql, null, e.getMessage());
        }
    }

    private ChatResult record(String question, PromptVersion version, long startedAt, Usage usage, Routed routed) {
        Outcome outcome = routed.outcome();
        int elapsedMs = (int) Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
        Integer rowCount = outcome.result() == null ? null : outcome.result().rows().size();

        ChatLog chatLog = new ChatLog(
                question,
                version.value(),
                llmClient.modelName(),
                outcome.action() == null ? null : outcome.action().value(),
                outcome.status().name(),
                outcome.sql(),
                rowCount,
                usage.attempts,
                usage.promptTokens,
                usage.completionTokens,
                elapsedMs,
                outcome.status() == ChatStatus.OK ? null : outcome.message());
        if (routed.route() != null) {
            chatLog.recordRoute(routed.route().value(), routed.toolName(), ToolArgsJson.write(routed.toolArgs()));
        }
        ChatLog saved = chatLogRepository.save(chatLog);

        return new ChatResult(outcome.action(), outcome.status(), outcome.message(), outcome.sql(),
                outcome.result(), version, llmClient.modelName(), usage.attempts, usage.promptTokens,
                usage.completionTokens, elapsedMs, saved.getId(), routed.route(), routed.toolName(),
                routed.toolArgs());
    }

    /** 질문 한 건에 쓴 LLM 호출 횟수와 토큰. 실패한 호출도 횟수에 든다. */
    private static final class Usage {
        private int attempts;
        private int promptTokens;
        private int completionTokens;
    }

    /** retryError가 있으면 SQL 실행 오류라서 한 번 더 물어볼 수 있다. */
    private record Outcome(Action action, ChatStatus status, String message, String sql, QueryResult result,
            String retryError) {

        static Outcome message(Action action, String message) {
            return new Outcome(action, ChatStatus.OK, message, null, null, null);
        }

        static Outcome failed(Action action, ChatStatus status, String message) {
            return new Outcome(action, status, message, null, null, null);
        }

        static Outcome unavailable() {
            return failed(null, ChatStatus.LLM_UNAVAILABLE, LLM_UNAVAILABLE_MESSAGE);
        }
    }

    /** 라우터가 고른 경로와 그 결과. v0~v2와 라우터 호출이 실패한 v3은 route가 null이다. */
    private record Routed(Route route, String toolName, Map<String, Object> toolArgs, Outcome outcome) {

        static Routed unrouted(Outcome outcome) {
            return new Routed(null, null, null, outcome);
        }
    }
}
