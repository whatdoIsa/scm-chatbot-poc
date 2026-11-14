package com.scmchat.service;

import com.scmchat.answer.AnswerStatus;
import com.scmchat.answer.NumberVerifier;
import com.scmchat.answer.Verification;
import com.scmchat.answer.VerificationContext;
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
import com.scmchat.tool.ToolRouteGuard;
import com.scmchat.tool.ToolValidation;
import com.scmchat.validator.SqlValidator;
import com.scmchat.validator.ValidationResult;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 질문을 프롬프트로 조립해 LLM에 보내고, 응답에 따라 SQL이나 조회 도구를 실행하거나 문장을 돌려준다.
 * LLM 호출 실패, 형식 오류, 실행 오류는 예외로 내보내지 않고 status로 바꾼다. 모든 요청은 chat_log에 한 행으로 남는다.
 * v2는 실행 전에 SQL을 검증하고, 실행 오류가 나면 오류 문구를 붙여 한 번만 다시 묻는다. 검증에서 거부된 SQL은 다시 묻지 않는다.
 * v3·v4는 라우터가 경로를 먼저 고른다(PRD 10.2, 11장). 도구 경로는 인자를 검증한 뒤 고정 쿼리를 실행하고,
 * SQL 경로는 v1 프롬프트에 v2의 검증과 재시도를 적용한다. 조회에 성공하면 결과를 문장으로 옮기게 하고
 * 문장의 숫자를 결과와 대조한다.
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);
    private static final String LLM_UNAVAILABLE_MESSAGE = "LLM server is unavailable";
    /** chat_log.tool_name, eval_result.tool_name의 길이(API_SPEC 8.5). */
    private static final int TOOL_NAME_MAX_LENGTH = 32;

    private final PromptBuilder promptBuilder;
    private final LlmClient llmClient;
    private final LlmOutputParser parser;
    private final SqlValidator validator;
    private final ReadOnlySqlExecutor executor;
    private final ToolArgsValidator toolArgsValidator;
    private final ToolExecutor toolExecutor;
    private final ToolRouteGuard toolRouteGuard;
    private final NumberVerifier numberVerifier;
    private final ChatLogRepository chatLogRepository;
    private final int validatorLimit;
    private final int answerMaxRows;
    private final LocalDate asOfDate;

    public ChatService(PromptBuilder promptBuilder, LlmClient llmClient, LlmOutputParser parser,
            SqlValidator validator, ReadOnlySqlExecutor executor, ToolArgsValidator toolArgsValidator,
            ToolExecutor toolExecutor, ToolRouteGuard toolRouteGuard, NumberVerifier numberVerifier,
            ChatLogRepository chatLogRepository, ChatProperties chatProperties) {
        this.promptBuilder = promptBuilder;
        this.llmClient = llmClient;
        this.parser = parser;
        this.validator = validator;
        this.executor = executor;
        this.toolArgsValidator = toolArgsValidator;
        this.toolExecutor = toolExecutor;
        this.toolRouteGuard = toolRouteGuard;
        this.numberVerifier = numberVerifier;
        this.chatLogRepository = chatLogRepository;
        this.validatorLimit = chatProperties.validatorLimit();
        this.answerMaxRows = chatProperties.answerMaxRows();
        this.asOfDate = chatProperties.asOfDate();
    }

    public ChatResult answer(String question, PromptVersion version) {
        long startedAt = System.nanoTime();
        Usage usage = new Usage();
        Routed routed;
        Answer answer = Answer.NONE;
        if (version.routed()) {
            routed = routeAndAnswer(question, version, usage);
            if (routed.outcome().result() != null) {
                answer = writeAnswer(question, routed, usage);
            }
        } else {
            boolean v2 = version == PromptVersion.V2;
            routed = Routed.unrouted(sqlStage(promptBuilder.build(version, question), v2, v2 ? 2 : 1, usage));
        }
        return record(question, version, startedAt, usage, routed, answer);
    }

    /**
     * 조회 결과를 한국어 문장으로 옮기게 하고 숫자를 대조한다(PRD 10.5). 이 단계의 실패는 조회 결과를 버리지 않는다.
     * 문장을 받지 못했으면 SKIPPED, 숫자가 결과와 맞지 않으면 REJECTED이고 어느 쪽이든 표는 그대로 응답한다.
     */
    private Answer writeAnswer(String question, Routed routed, Usage usage) {
        QueryResult result = routed.outcome().result();
        if (result.rows().isEmpty() || result.rows().size() > answerMaxRows || result.truncated()) {
            return Answer.SKIPPED;
        }
        try {
            String text = parser.parseAnswer(
                    call(promptBuilder.buildAnswer(question, result), OutputFormat.ANSWER, usage).rawContent());
            if (text == null) {
                return Answer.SKIPPED;
            }
            Verification verification = numberVerifier.verify(text,
                    new VerificationContext(question, result, routed.toolCall(), asOfDate));
            if (!verification.ok()) {
                log.info("answer rejected, numbers not found in the result: {}", verification.unverified());
            }
            return new Answer(text, verification.ok() ? AnswerStatus.VERIFIED : AnswerStatus.REJECTED);
        } catch (FormatException e) {
            return Answer.SKIPPED;
        } catch (RuntimeException e) {
            // 답변은 덧붙이는 단계다. 여기서 무엇이 실패하든 이미 얻은 조회 결과와 로그 한 행은 지킨다(PRD 10.2).
            log.warn("answer step failed; the query result is returned without a sentence", e);
            return Answer.SKIPPED;
        }
    }

    /** v3과 v4는 같은 흐름이고 라우터의 메시지와 출력 형식만 다르다(API_SPEC 9.4). */
    private Routed routeAndAnswer(String question, PromptVersion version, Usage usage) {
        OutputFormat routeFormat = version == PromptVersion.V3 ? OutputFormat.ROUTE : OutputFormat.ROUTE_V4;
        LlmReply reply;
        try {
            reply = call(promptBuilder.buildRouter(version, question), routeFormat, usage);
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
            case CLARIFY -> Routed.of(Route.CLARIFY, Outcome.message(Action.CLARIFY, parsed.message()));
            case REFUSE -> Routed.of(Route.REFUSE, Outcome.message(Action.REFUSE, parsed.message()));
            // SQL 단계가 clarify나 refuse를 내면 그 action이 최종 결과다.
            case SQL -> Routed.of(Route.SQL, sqlStage(promptBuilder.build(PromptVersion.V1, question), true, 2, usage));
            case TOOL -> version == PromptVersion.V5 ? runGuardedTool(question, parsed, usage) : runTool(parsed);
        };
    }

    /** 인자 검증에 실패하면 SQL 경로로 넘기거나 다시 묻지 않는다. 라우터의 인자 오류를 그대로 드러낸다. */
    private Routed runTool(ParsedRoute parsed) {
        // 검증에 실패한 호출은 모델이 낸 이름과 인자를 그대로 남긴다. 이름은 tool_name 컬럼 길이에 맞춘다.
        String rawName = abbreviate(parsed.tool(), TOOL_NAME_MAX_LENGTH);
        ToolValidation validation;
        try {
            validation = toolArgsValidator.validate(parsed.tool(), parsed.args());
        } catch (SqlExecutionException e) {
            // 품목·거점을 확인하는 조회가 실패했다. 인자가 틀린 것이 아니므로 거부가 아니라 실행 오류다.
            return new Routed(Route.TOOL, rawName, parsed.args().toArgsMap(), null,
                    Outcome.failed(Action.TOOL, ChatStatus.SQL_EXECUTION_ERROR, e.getMessage()));
        }
        if (!validation.ok()) {
            return new Routed(Route.TOOL, rawName, parsed.args().toArgsMap(), null,
                    Outcome.failed(Action.TOOL, ChatStatus.TOOL_REJECTED, validation.reason()));
        }
        ToolCall call = validation.call();
        Outcome outcome;
        try {
            outcome = new Outcome(Action.TOOL, ChatStatus.OK, null, null, toolExecutor.execute(call), null);
        } catch (SqlExecutionException e) {
            outcome = Outcome.failed(Action.TOOL, ChatStatus.SQL_EXECUTION_ERROR, e.getMessage());
        }
        return new Routed(Route.TOOL, call.tool().value(), call.toArgsMap(), call, outcome);
    }

    /**
     * v5의 도구 경로(PRD 12.2). 인자 검증에 실패했거나 도구가 질문과 맞지 않으면 도구를 실행하지 않고 SQL 단계로 보낸다.
     * 그때 route는 sql이고, 라우터가 냈던 도구 이름과 인자에 막은 이유(guard)를 붙여 로그에 남긴다.
     */
    private Routed runGuardedTool(String question, ParsedRoute parsed, Usage usage) {
        ToolValidation validation;
        try {
            validation = toolArgsValidator.validate(parsed.tool(), parsed.args());
        } catch (SqlExecutionException e) {
            return new Routed(Route.TOOL, abbreviate(parsed.tool(), TOOL_NAME_MAX_LENGTH), parsed.args().toArgsMap(),
                    null, Outcome.failed(Action.TOOL, ChatStatus.SQL_EXECUTION_ERROR, e.getMessage()));
        }
        if (!validation.ok()) {
            return toSqlStage(question, abbreviate(parsed.tool(), TOOL_NAME_MAX_LENGTH), parsed.args().toArgsMap(),
                    "rejected: " + validation.reason(), usage);
        }
        ToolRouteGuard.Decision decision = toolRouteGuard.check(question, validation.call());
        if (decision.reroute()) {
            return toSqlStage(question, validation.call().tool().value(), validation.call().toArgsMap(),
                    decision.rerouteReason(), usage);
        }
        ToolCall call = decision.call();
        Map<String, Object> args = call.toArgsMap();
        if (decision.orderCorrected()) {
            args.put("guard", "order corrected");
        }
        Outcome outcome;
        try {
            outcome = new Outcome(Action.TOOL, ChatStatus.OK, null, null, toolExecutor.execute(call), null);
        } catch (SqlExecutionException e) {
            outcome = Outcome.failed(Action.TOOL, ChatStatus.SQL_EXECUTION_ERROR, e.getMessage());
        }
        return new Routed(Route.TOOL, call.tool().value(), args, call, outcome);
    }

    private Routed toSqlStage(String question, String toolName, Map<String, Object> toolArgs, String reason,
            Usage usage) {
        Map<String, Object> args = new java.util.LinkedHashMap<>(toolArgs);
        args.put("guard", reason);
        log.info("tool route sent to the sql stage: {} {}", toolName, reason);
        return new Routed(Route.SQL, toolName, args, null,
                sqlStage(promptBuilder.build(PromptVersion.V1, question), true, 2, usage));
    }

    private static String abbreviate(String value, int maxLength) {
        return value == null || value.length() <= maxLength ? value : value.substring(0, maxLength);
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

    private ChatResult record(String question, PromptVersion version, long startedAt, Usage usage, Routed routed,
            Answer answer) {
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
        if (answer.status() != null) {
            chatLog.recordAnswer(answer.text(), answer.status().name());
        }
        ChatLog saved = chatLogRepository.save(chatLog);

        return new ChatResult(outcome.action(), outcome.status(), outcome.message(), outcome.sql(),
                outcome.result(), version, llmClient.modelName(), usage.attempts, usage.promptTokens,
                usage.completionTokens, elapsedMs, saved.getId(), routed.route(), routed.toolName(),
                routed.toolArgs(), answer.text(), answer.status());
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

    /** 라우터가 고른 경로와 그 결과. v0~v2와 라우터 호출이 실패한 v3·v4는 route가 null이다. toolCall은 검증을 통과한 호출이다. */
    private record Routed(Route route, String toolName, Map<String, Object> toolArgs, ToolCall toolCall,
            Outcome outcome) {

        static Routed unrouted(Outcome outcome) {
            return new Routed(null, null, null, null, outcome);
        }

        static Routed of(Route route, Outcome outcome) {
            return new Routed(route, null, null, null, outcome);
        }
    }

    /** 답변 문장 원문과 후검증 결과. 조회 결과가 없으면(v0~v2 포함) 둘 다 null이다. */
    private record Answer(String text, AnswerStatus status) {

        static final Answer NONE = new Answer(null, null);
        static final Answer SKIPPED = new Answer(null, AnswerStatus.SKIPPED);
    }
}
