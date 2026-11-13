package com.scmchat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.scmchat.answer.AnswerStatus;
import com.scmchat.answer.NumberVerifier;
import com.scmchat.config.ChatProperties;
import com.scmchat.entity.ChatLog;
import com.scmchat.executor.QueryResult;
import com.scmchat.executor.ReadOnlySqlExecutor;
import com.scmchat.executor.SqlExecutionException;
import com.scmchat.llm.Action;
import com.scmchat.llm.FakeLlmClient;
import com.scmchat.llm.LlmClient;
import com.scmchat.llm.LlmMessage;
import com.scmchat.llm.LlmOutputParser;
import com.scmchat.llm.LlmReply;
import com.scmchat.llm.LlmUnavailableException;
import com.scmchat.llm.OutputFormat;
import com.scmchat.llm.Route;
import com.scmchat.prompt.PromptBuilder;
import com.scmchat.prompt.PromptVersion;
import com.scmchat.repository.ChatLogRepository;
import com.scmchat.tool.Dimensions;
import com.scmchat.tool.ToolArgsValidator;
import com.scmchat.tool.ToolCall;
import com.scmchat.tool.ToolExecutor;
import com.scmchat.tool.ToolName;
import com.scmchat.tool.ToolOutput;
import com.scmchat.validator.SqlValidator;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

class ChatServiceTest {

    private static final String QUESTION = "SKU-007의 현재 재고는?";
    private static final long CHAT_LOG_ID = 7L;
    private static final String ROUTE_SQL = "{\"route\":\"sql\",\"message\":\"\"}";
    private static final String ROUTE_DAYS_OF_SUPPLY = "{\"route\":\"tool\",\"tool\":\"days_of_supply\","
            + "\"sku\":\"SKU-7\",\"location\":\"서울센터\",\"days\":0,\"start_date\":\"\",\"end_date\":\"\","
            + "\"output\":\"value\",\"top_n\":0,\"order\":\"\",\"message\":\"\"}";
    private static final Dimensions DIMENSIONS = new Dimensions() {
        @Override
        public boolean skuExists(String sku) {
            return "SKU-007".equals(sku);
        }

        @Override
        public List<String> locationNames() {
            return List.of("서울센터", "부산센터");
        }
    };

    private final FakeLlmClient fakeLlm = new FakeLlmClient();
    private final ReadOnlySqlExecutor executor = mock(ReadOnlySqlExecutor.class);
    private final ChatLogRepository chatLogRepository = mock(ChatLogRepository.class);
    private final ToolExecutor toolExecutor = mock(ToolExecutor.class);
    private final ChatService service = serviceWith(fakeLlm);

    @BeforeEach
    void assignIdOnSave() {
        when(chatLogRepository.save(any(ChatLog.class))).thenAnswer(invocation -> {
            ChatLog chatLog = invocation.getArgument(0);
            ReflectionTestUtils.setField(chatLog, "id", CHAT_LOG_ID);
            return chatLog;
        });
    }

    @Test
    void executesSqlAndReturnsRows() {
        QueryResult rows = new QueryResult(List.of("on_hand"), List.of(List.of(120)), false);
        when(executor.execute("SELECT on_hand FROM inventory_daily")).thenReturn(rows);
        fakeLlm.enqueue("{\"action\":\"sql\",\"sql\":\"SELECT on_hand FROM inventory_daily;\",\"message\":\"\"}");

        ChatResult result = service.answer(QUESTION, PromptVersion.V0);

        assertThat(result.action()).isEqualTo(Action.SQL);
        assertThat(result.status()).isEqualTo(ChatStatus.OK);
        assertThat(result.sql()).isEqualTo("SELECT on_hand FROM inventory_daily");
        assertThat(result.result()).isEqualTo(rows);
        assertThat(result.message()).isNull();
        assertThat(result.promptVersion()).isEqualTo(PromptVersion.V0);
        assertThat(result.model()).isEqualTo("fake");
        assertThat(result.attempts()).isEqualTo(1);
        assertThat(result.chatLogId()).isEqualTo(CHAT_LOG_ID);
    }

    @Test
    void returnsClarifyMessageWithoutExecuting() {
        fakeLlm.enqueue("{\"action\":\"clarify\",\"sql\":\"\",\"message\":\"어느 품목의 재고인가요?\"}");

        ChatResult result = service.answer("재고 어때?", PromptVersion.V0);

        assertThat(result.action()).isEqualTo(Action.CLARIFY);
        assertThat(result.status()).isEqualTo(ChatStatus.OK);
        assertThat(result.message()).isEqualTo("어느 품목의 재고인가요?");
        assertThat(result.sql()).isNull();
        assertThat(result.result()).isNull();
        verify(executor, never()).execute(any());
    }

    @Test
    void returnsRefuseMessageWithoutExecuting() {
        fakeLlm.enqueue("{\"action\":\"refuse\",\"sql\":\"\",\"message\":\"데이터를 변경할 수 없습니다.\"}");

        ChatResult result = service.answer("SKU-007 재고를 0으로 바꿔줘", PromptVersion.V0);

        assertThat(result.action()).isEqualTo(Action.REFUSE);
        assertThat(result.status()).isEqualTo(ChatStatus.OK);
        assertThat(result.message()).isEqualTo("데이터를 변경할 수 없습니다.");
        verify(executor, never()).execute(any());
    }

    @Test
    void turnsUnparseableOutputIntoFormatError() {
        fakeLlm.enqueue("Sure! Here is the SQL: SELECT 1");

        ChatResult result = service.answer(QUESTION, PromptVersion.V0);

        assertThat(result.action()).isNull();
        assertThat(result.status()).isEqualTo(ChatStatus.FORMAT_ERROR);
        assertThat(result.message()).contains("not a single JSON object");
        verify(executor, never()).execute(any());
    }

    @Test
    void turnsSqlActionWithoutSqlIntoFormatError() {
        fakeLlm.enqueue("{\"action\":\"sql\",\"sql\":\"\",\"message\":\"\"}");

        ChatResult result = service.answer(QUESTION, PromptVersion.V0);

        assertThat(result.action()).isNull();
        assertThat(result.status()).isEqualTo(ChatStatus.FORMAT_ERROR);
    }

    @Test
    void turnsExecutionFailureIntoSqlExecutionErrorWithoutRetry() {
        when(executor.execute("SELECT stock FROM inventory"))
                .thenThrow(new SqlExecutionException("ERROR: relation \"inventory\" does not exist", "42P01", null));
        fakeLlm.enqueue("{\"action\":\"sql\",\"sql\":\"SELECT stock FROM inventory\",\"message\":\"\"}");

        ChatResult result = service.answer(QUESTION, PromptVersion.V0);

        assertThat(result.action()).isEqualTo(Action.SQL);
        assertThat(result.status()).isEqualTo(ChatStatus.SQL_EXECUTION_ERROR);
        assertThat(result.sql()).isEqualTo("SELECT stock FROM inventory");
        assertThat(result.message()).contains("relation \"inventory\" does not exist");
        assertThat(result.result()).isNull();
        assertThat(result.attempts()).isEqualTo(1);
        verify(executor, times(1)).execute(any());
    }

    @Test
    void turnsLlmFailureIntoLlmUnavailable() {
        ChatService failing = serviceWith(new StubLlmClient(null));

        ChatResult result = failing.answer(QUESTION, PromptVersion.V0);

        assertThat(result.action()).isNull();
        assertThat(result.status()).isEqualTo(ChatStatus.LLM_UNAVAILABLE);
        assertThat(result.message()).isEqualTo("LLM server is unavailable");
        assertThat(result.promptTokens()).isZero();
        assertThat(result.completionTokens()).isZero();
        verify(executor, never()).execute(any());
    }

    @Test
    void savesOneChatLogRowForSuccessfulSql() {
        when(executor.execute(any())).thenReturn(
                new QueryResult(List.of("sku"), List.of(List.of("SKU-001"), List.of("SKU-002")), false));
        ChatService stubbed = serviceWith(new StubLlmClient(
                new LlmReply("{\"action\":\"sql\",\"sql\":\"SELECT sku FROM product\",\"message\":\"\"}", 512, 37)));

        ChatResult result = stubbed.answer(QUESTION, PromptVersion.V0);

        ChatLog saved = savedChatLog();
        assertThat(saved.getQuestion()).isEqualTo(QUESTION);
        assertThat(saved.getPromptVersion()).isEqualTo("v0");
        assertThat(saved.getModel()).isEqualTo("stub-model");
        assertThat(saved.getAction()).isEqualTo("sql");
        assertThat(saved.getStatus()).isEqualTo("OK");
        assertThat(saved.getGeneratedSql()).isEqualTo("SELECT sku FROM product");
        assertThat(saved.getRowCount()).isEqualTo(2);
        assertThat(saved.getAttempts()).isEqualTo(1);
        assertThat(saved.getPromptTokens()).isEqualTo(512);
        assertThat(saved.getCompletionTokens()).isEqualTo(37);
        assertThat(saved.getErrorMessage()).isNull();
        assertThat(result.promptTokens()).isEqualTo(512);
        assertThat(result.completionTokens()).isEqualTo(37);
        assertThat(result.elapsedMs()).isEqualTo(saved.getElapsedMs());
    }

    @Test
    void savesFailureReasonInChatLog() {
        fakeLlm.enqueue("not json");

        service.answer(QUESTION, PromptVersion.V0);

        ChatLog saved = savedChatLog();
        assertThat(saved.getAction()).isNull();
        assertThat(saved.getStatus()).isEqualTo("FORMAT_ERROR");
        assertThat(saved.getGeneratedSql()).isNull();
        assertThat(saved.getRowCount()).isNull();
        assertThat(saved.getErrorMessage()).isNotBlank();
    }

    @Test
    void doesNotStoreClarifyMessageAsError() {
        service.answer("재고 어때?", PromptVersion.V0);

        ChatLog saved = savedChatLog();
        assertThat(saved.getAction()).isEqualTo("clarify");
        assertThat(saved.getStatus()).isEqualTo("OK");
        assertThat(saved.getErrorMessage()).isNull();
    }

    @Test
    void v2ExecutesValidatedSqlWithLimit() {
        when(executor.execute("SELECT sku FROM product LIMIT 200")).thenReturn(
                new QueryResult(List.of("sku"), List.of(List.of("SKU-001")), false));
        fakeLlm.enqueue("{\"action\":\"sql\",\"sql\":\"SELECT sku FROM product\",\"message\":\"\"}");

        ChatResult result = service.answer(QUESTION, PromptVersion.V2);

        assertThat(result.status()).isEqualTo(ChatStatus.OK);
        assertThat(result.sql()).isEqualTo("SELECT sku FROM product LIMIT 200");
        assertThat(result.attempts()).isEqualTo(1);
    }

    @Test
    void v2RejectsInvalidSqlWithoutExecutingOrRetrying() {
        fakeLlm.enqueue("{\"action\":\"sql\",\"sql\":\"DELETE FROM supply_policy\",\"message\":\"\"}",
                "{\"action\":\"sql\",\"sql\":\"SELECT 1\",\"message\":\"\"}");

        ChatResult result = service.answer("공급 정책 데이터를 전부 삭제해줘", PromptVersion.V2);

        assertThat(result.action()).isEqualTo(Action.SQL);
        assertThat(result.status()).isEqualTo(ChatStatus.SQL_REJECTED);
        assertThat(result.sql()).isEqualTo("DELETE FROM supply_policy");
        assertThat(result.message()).contains("not allowed");
        assertThat(result.attempts()).isEqualTo(1);
        verify(executor, never()).execute(any());
    }

    @Test
    void v2RetriesOnceAfterExecutionErrorAndSucceeds() {
        when(executor.execute("SELECT stock FROM inventory_daily LIMIT 200"))
                .thenThrow(new SqlExecutionException("ERROR: column \"stock\" does not exist", "42703", null));
        QueryResult rows = new QueryResult(List.of("on_hand"), List.of(List.of(120)), false);
        when(executor.execute("SELECT on_hand FROM inventory_daily LIMIT 200")).thenReturn(rows);
        ChatService stubbed = serviceWith(new ScriptedLlmClient(
                new LlmReply("{\"action\":\"sql\",\"sql\":\"SELECT stock FROM inventory_daily\",\"message\":\"\"}", 500, 20),
                new LlmReply("{\"action\":\"sql\",\"sql\":\"SELECT on_hand FROM inventory_daily\",\"message\":\"\"}", 560, 22)));

        ChatResult result = stubbed.answer(QUESTION, PromptVersion.V2);

        assertThat(result.status()).isEqualTo(ChatStatus.OK);
        assertThat(result.result()).isEqualTo(rows);
        assertThat(result.sql()).isEqualTo("SELECT on_hand FROM inventory_daily LIMIT 200");
        assertThat(result.attempts()).isEqualTo(2);
        assertThat(result.promptTokens()).isEqualTo(1060);
        assertThat(result.completionTokens()).isEqualTo(42);
        ChatLog saved = savedChatLog();
        assertThat(saved.getAttempts()).isEqualTo(2);
        assertThat(saved.getStatus()).isEqualTo("OK");
    }

    @Test
    void v2ReturnsExecutionErrorWhenRetryAlsoFails() {
        when(executor.execute(any()))
                .thenThrow(new SqlExecutionException("ERROR: column \"stock\" does not exist", "42703", null));
        fakeLlm.enqueue("{\"action\":\"sql\",\"sql\":\"SELECT stock FROM inventory_daily\",\"message\":\"\"}",
                "{\"action\":\"sql\",\"sql\":\"SELECT stock FROM product\",\"message\":\"\"}",
                "{\"action\":\"sql\",\"sql\":\"SELECT 1\",\"message\":\"\"}");

        ChatResult result = service.answer(QUESTION, PromptVersion.V2);

        assertThat(result.status()).isEqualTo(ChatStatus.SQL_EXECUTION_ERROR);
        assertThat(result.sql()).isEqualTo("SELECT stock FROM product LIMIT 200");
        assertThat(result.attempts()).isEqualTo(2);
        verify(executor, times(2)).execute(any());
    }

    @Test
    void v2SendsPostgresErrorBackToTheModelOnRetry() {
        when(executor.execute("SELECT stock FROM inventory_daily LIMIT 200"))
                .thenThrow(new SqlExecutionException("ERROR: column \"stock\" does not exist", "42703", null));
        when(executor.execute("SELECT 1 LIMIT 200")).thenReturn(new QueryResult(List.of("v"), List.of(List.of(1)), false));
        ScriptedLlmClient scripted = new ScriptedLlmClient(
                new LlmReply("{\"action\":\"sql\",\"sql\":\"SELECT stock FROM inventory_daily\",\"message\":\"\"}", 1, 1),
                new LlmReply("{\"action\":\"sql\",\"sql\":\"SELECT 1\",\"message\":\"\"}", 1, 1));

        serviceWith(scripted).answer(QUESTION, PromptVersion.V2);

        List<LlmMessage> second = scripted.requests.get(1);
        assertThat(second).hasSize(4);
        assertThat(second.get(2).role()).isEqualTo("assistant");
        assertThat(second.get(3).content()).contains("ERROR: column \"stock\" does not exist");
    }

    @Test
    void v2KeepsFirstExecutionErrorWhenRetryAnswersWithoutSql() {
        when(executor.execute(any()))
                .thenThrow(new SqlExecutionException("ERROR: column \"stock\" does not exist", "42703", null));
        fakeLlm.enqueue("{\"action\":\"sql\",\"sql\":\"SELECT stock FROM inventory_daily\",\"message\":\"\"}",
                "{\"action\":\"refuse\",\"sql\":\"\",\"message\":\"답할 수 없습니다.\"}");

        ChatResult result = service.answer(QUESTION, PromptVersion.V2);

        assertThat(result.action()).isEqualTo(Action.SQL);
        assertThat(result.status()).isEqualTo(ChatStatus.SQL_EXECUTION_ERROR);
        assertThat(result.sql()).isEqualTo("SELECT stock FROM inventory_daily LIMIT 200");
        assertThat(result.attempts()).isEqualTo(2);
    }

    @Test
    void v2MarksResultTruncatedWhenValidatorLimitFillsUp() {
        List<List<Object>> rows = java.util.Collections.nCopies(200, List.<Object>of("x"));
        when(executor.execute("SELECT sku FROM sales_daily LIMIT 200"))
                .thenReturn(new QueryResult(List.of("sku"), rows, false));
        when(executor.execute("SELECT sku FROM sales_daily LIMIT 200 OFFSET 0"))
                .thenReturn(new QueryResult(List.of("sku"), rows, false));
        fakeLlm.enqueue("{\"action\":\"sql\",\"sql\":\"SELECT sku FROM sales_daily\",\"message\":\"\"}",
                "{\"action\":\"sql\",\"sql\":\"SELECT sku FROM sales_daily LIMIT 200 OFFSET 0\",\"message\":\"\"}");

        assertThat(service.answer(QUESTION, PromptVersion.V2).result().truncated()).isTrue();
        assertThat(service.answer(QUESTION, PromptVersion.V2).result().truncated()).isFalse();
    }

    @Test
    void v1DoesNotValidateOrRetry() {
        when(executor.execute("SELECT stock FROM inventory_daily"))
                .thenThrow(new SqlExecutionException("ERROR: column \"stock\" does not exist", "42703", null));
        fakeLlm.enqueue("{\"action\":\"sql\",\"sql\":\"SELECT stock FROM inventory_daily\",\"message\":\"\"}");

        ChatResult result = service.answer(QUESTION, PromptVersion.V1);

        assertThat(result.status()).isEqualTo(ChatStatus.SQL_EXECUTION_ERROR);
        assertThat(result.sql()).isEqualTo("SELECT stock FROM inventory_daily");
        assertThat(result.attempts()).isEqualTo(1);
    }

    @Test
    void v0ToV2AreNotRouted() {
        fakeLlm.enqueue("{\"action\":\"clarify\",\"sql\":\"\",\"message\":\"어느 품목인가요?\"}");

        ChatResult result = service.answer(QUESTION, PromptVersion.V1);

        assertThat(result.route()).isNull();
        assertThat(result.toolName()).isNull();
        assertThat(result.toolArgs()).isNull();
        assertThat(savedChatLog().getRoute()).isNull();
        verify(toolExecutor, never()).execute(any());
    }

    @Test
    void v3ToolRouteValidatesArgumentsAndRunsTheTool() {
        QueryResult rows = new QueryResult(List.of("days_of_supply"), List.of(List.<Object>of(12.5)), false);
        ToolCall expectedCall = new ToolCall(ToolName.DAYS_OF_SUPPLY, ToolOutput.VALUE, "SKU-007", "서울센터",
                null, null, null, null, null);
        when(toolExecutor.execute(expectedCall)).thenReturn(rows);
        ScriptedLlmClient scripted = new ScriptedLlmClient(new LlmReply(ROUTE_DAYS_OF_SUPPLY, 900, 40),
                new LlmReply("{\"answer\":\"서울센터 SKU-007의 재고보유일수는 12.5일입니다.\"}", 150, 25));

        ChatResult result = serviceWith(scripted).answer("서울센터 SKU-7의 재고보유일수는?", PromptVersion.V3);

        assertThat(result.action()).isEqualTo(Action.TOOL);
        assertThat(result.status()).isEqualTo(ChatStatus.OK);
        assertThat(result.route()).isEqualTo(Route.TOOL);
        assertThat(result.toolName()).isEqualTo("days_of_supply");
        assertThat(result.toolArgs()).containsExactly(
                java.util.Map.entry("sku", "SKU-007"), java.util.Map.entry("location", "서울센터"),
                java.util.Map.entry("output", "value"));
        assertThat(result.result()).isEqualTo(rows);
        assertThat(result.sql()).isNull();
        assertThat(result.promptVersion()).isEqualTo(PromptVersion.V3);
        assertThat(result.attempts()).isEqualTo(2);
        assertThat(result.promptTokens()).isEqualTo(1050);
        assertThat(result.completionTokens()).isEqualTo(65);
        assertThat(scripted.formats).containsExactly(OutputFormat.ROUTE, OutputFormat.ANSWER);
        assertThat(result.answerStatus()).isEqualTo(AnswerStatus.VERIFIED);
        assertThat(result.answerText()).isEqualTo("서울센터 SKU-007의 재고보유일수는 12.5일입니다.");
        // 답변 단계는 질문과 결과 표만 받는다.
        assertThat(scripted.requests.get(1).get(1).content())
                .isEqualTo("Question: 서울센터 SKU-7의 재고보유일수는?\nColumns: days_of_supply\nRows:\n12.5");
        verify(executor, never()).execute(any());

        ChatLog saved = savedChatLog();
        assertThat(saved.getAction()).isEqualTo("tool");
        assertThat(saved.getRoute()).isEqualTo("tool");
        assertThat(saved.getToolName()).isEqualTo("days_of_supply");
        assertThat(saved.getToolArgs())
                .isEqualTo("{\"sku\":\"SKU-007\",\"location\":\"서울센터\",\"output\":\"value\"}");
        assertThat(saved.getGeneratedSql()).isNull();
        assertThat(saved.getRowCount()).isEqualTo(1);
        assertThat(saved.getErrorMessage()).isNull();
        assertThat(saved.getAnswerStatus()).isEqualTo("VERIFIED");
        assertThat(saved.getAnswerText()).isEqualTo("서울센터 SKU-007의 재고보유일수는 12.5일입니다.");
    }

    @Test
    void v3RejectsInvalidToolArgumentsWithoutFallingBackToSql() {
        ScriptedLlmClient scripted = new ScriptedLlmClient(new LlmReply(
                "{\"route\":\"tool\",\"tool\":\"days_of_supply\",\"location\":\"서울\",\"output\":\"value\"}", 0, 0));

        ChatResult result = serviceWith(scripted).answer("서울 재고보유일수는?", PromptVersion.V3);

        assertThat(result.action()).isEqualTo(Action.TOOL);
        assertThat(result.status()).isEqualTo(ChatStatus.TOOL_REJECTED);
        assertThat(result.message()).contains("unknown location: 서울");
        assertThat(result.route()).isEqualTo(Route.TOOL);
        assertThat(result.toolName()).isEqualTo("days_of_supply");
        assertThat(result.toolArgs()).containsEntry("location", "서울").containsEntry("output", "value");
        assertThat(result.result()).isNull();
        assertThat(result.attempts()).isEqualTo(1);
        verify(toolExecutor, never()).execute(any());
        verify(executor, never()).execute(any());
        ChatLog saved = savedChatLog();
        assertThat(saved.getStatus()).isEqualTo("TOOL_REJECTED");
        assertThat(saved.getErrorMessage()).contains("unknown location");
    }

    @Test
    void v3TurnsToolQueryFailureIntoExecutionErrorWithoutRetry() {
        when(toolExecutor.execute(any()))
                .thenThrow(new SqlExecutionException("ERROR: canceling statement due to statement timeout", "57014", null));
        ScriptedLlmClient scripted = new ScriptedLlmClient(new LlmReply(ROUTE_DAYS_OF_SUPPLY, 0, 0));

        ChatResult result = serviceWith(scripted).answer(QUESTION, PromptVersion.V3);

        assertThat(result.action()).isEqualTo(Action.TOOL);
        assertThat(result.status()).isEqualTo(ChatStatus.SQL_EXECUTION_ERROR);
        assertThat(result.message()).contains("statement timeout");
        assertThat(result.toolName()).isEqualTo("days_of_supply");
        assertThat(result.attempts()).isEqualTo(1);
    }

    @Test
    void v3TurnsDatabaseFailureDuringArgumentValidationIntoExecutionError() {
        Dimensions databaseDown = new Dimensions() {
            @Override
            public boolean skuExists(String sku) {
                throw new SqlExecutionException("FATAL: the database system is shutting down", "57P01", null);
            }

            @Override
            public List<String> locationNames() {
                throw new SqlExecutionException("FATAL: the database system is shutting down", "57P01", null);
            }
        };
        ChatProperties properties = properties();
        ChatService stubbed = new ChatService(new PromptBuilder(properties),
                new ScriptedLlmClient(new LlmReply(ROUTE_DAYS_OF_SUPPLY, 0, 0)), new LlmOutputParser(),
                new SqlValidator(properties), executor, new ToolArgsValidator(databaseDown, properties), toolExecutor,
                new NumberVerifier(), chatLogRepository, properties);

        ChatResult result = stubbed.answer(QUESTION, PromptVersion.V3);

        assertThat(result.action()).isEqualTo(Action.TOOL);
        assertThat(result.status()).isEqualTo(ChatStatus.SQL_EXECUTION_ERROR);
        assertThat(result.message()).contains("shutting down");
        assertThat(result.route()).isEqualTo(Route.TOOL);
        assertThat(result.toolName()).isEqualTo("days_of_supply");
        assertThat(result.toolArgs()).containsEntry("sku", "SKU-7");
        assertThat(savedChatLog().getStatus()).isEqualTo("SQL_EXECUTION_ERROR");
        verify(toolExecutor, never()).execute(any());
    }

    @Test
    void v3SqlRouteUsesTheV1PromptThenValidatesAndExecutes() {
        QueryResult rows = new QueryResult(List.of("qty"), List.of(List.of(31)), false);
        when(executor.execute("SELECT qty FROM sales_daily LIMIT 200")).thenReturn(rows);
        ScriptedLlmClient scripted = new ScriptedLlmClient(new LlmReply(ROUTE_SQL, 800, 30),
                new LlmReply("{\"action\":\"sql\",\"sql\":\"SELECT qty FROM sales_daily\",\"message\":\"\"}", 2000, 60),
                new LlmReply("{\"answer\":\"판매량은 31개입니다.\"}", 0, 0));
        ChatService stubbed = serviceWith(scripted);

        ChatResult result = stubbed.answer(QUESTION, PromptVersion.V3);

        assertThat(result.action()).isEqualTo(Action.SQL);
        assertThat(result.status()).isEqualTo(ChatStatus.OK);
        assertThat(result.route()).isEqualTo(Route.SQL);
        assertThat(result.toolName()).isNull();
        assertThat(result.sql()).isEqualTo("SELECT qty FROM sales_daily LIMIT 200");
        assertThat(result.result()).isEqualTo(rows);
        assertThat(result.attempts()).isEqualTo(3);
        assertThat(result.promptTokens()).isEqualTo(2800);
        assertThat(result.completionTokens()).isEqualTo(90);
        assertThat(scripted.formats).containsExactly(OutputFormat.ROUTE, OutputFormat.ACTION, OutputFormat.ANSWER);
        assertThat(result.answerStatus()).isEqualTo(AnswerStatus.VERIFIED);
        // SQL 단계에 보내는 메시지는 v1 측정 때의 프롬프트와 같다.
        assertThat(scripted.requests.get(1))
                .isEqualTo(new PromptBuilder(properties()).build(PromptVersion.V1, QUESTION));
        ChatLog saved = savedChatLog();
        assertThat(saved.getRoute()).isEqualTo("sql");
        assertThat(saved.getToolName()).isNull();
        assertThat(saved.getToolArgs()).isNull();
        verify(toolExecutor, never()).execute(any());
    }

    @Test
    void v3SqlRouteRejectsInvalidSqlLikeV2() {
        ScriptedLlmClient scripted = new ScriptedLlmClient(new LlmReply(ROUTE_SQL, 0, 0),
                new LlmReply("{\"action\":\"sql\",\"sql\":\"DELETE FROM supply_policy\",\"message\":\"\"}", 0, 0));

        ChatResult result = serviceWith(scripted).answer(QUESTION, PromptVersion.V3);

        assertThat(result.status()).isEqualTo(ChatStatus.SQL_REJECTED);
        assertThat(result.route()).isEqualTo(Route.SQL);
        assertThat(result.attempts()).isEqualTo(2);
        verify(executor, never()).execute(any());
    }

    @Test
    void v3SqlRouteRetriesOnceAfterExecutionError() {
        when(executor.execute("SELECT stock FROM inventory_daily LIMIT 200"))
                .thenThrow(new SqlExecutionException("ERROR: column \"stock\" does not exist", "42703", null));
        QueryResult rows = new QueryResult(List.of("on_hand"), List.of(List.of(120)), false);
        when(executor.execute("SELECT on_hand FROM inventory_daily LIMIT 200")).thenReturn(rows);
        ScriptedLlmClient scripted = new ScriptedLlmClient(new LlmReply(ROUTE_SQL, 0, 0),
                new LlmReply("{\"action\":\"sql\",\"sql\":\"SELECT stock FROM inventory_daily\",\"message\":\"\"}", 0, 0),
                new LlmReply("{\"action\":\"sql\",\"sql\":\"SELECT on_hand FROM inventory_daily\",\"message\":\"\"}", 0, 0),
                new LlmReply("{\"answer\":\"현재 재고는 120개입니다.\"}", 0, 0));

        ChatResult result = serviceWith(scripted).answer(QUESTION, PromptVersion.V3);

        assertThat(result.status()).isEqualTo(ChatStatus.OK);
        assertThat(result.result()).isEqualTo(rows);
        assertThat(result.attempts()).isEqualTo(4);
        assertThat(scripted.formats).containsExactly(
                OutputFormat.ROUTE, OutputFormat.ACTION, OutputFormat.ACTION, OutputFormat.ANSWER);
        assertThat(scripted.requests.get(2).get(3).content()).contains("column \"stock\" does not exist");
    }

    @Test
    void v3TakesClarifyFromTheSqlStageAsTheFinalAction() {
        ScriptedLlmClient scripted = new ScriptedLlmClient(new LlmReply(ROUTE_SQL, 0, 0),
                new LlmReply("{\"action\":\"clarify\",\"sql\":\"\",\"message\":\"어느 기간인가요?\"}", 0, 0));

        ChatResult result = serviceWith(scripted).answer("판매량 알려줘", PromptVersion.V3);

        assertThat(result.action()).isEqualTo(Action.CLARIFY);
        assertThat(result.status()).isEqualTo(ChatStatus.OK);
        assertThat(result.message()).isEqualTo("어느 기간인가요?");
        assertThat(result.route()).isEqualTo(Route.SQL);
        assertThat(result.attempts()).isEqualTo(2);
        verify(executor, never()).execute(any());
    }

    @Test
    void v3ClarifyAndRefuseRoutesAnswerWithoutASecondCall() {
        ScriptedLlmClient scripted = new ScriptedLlmClient(
                new LlmReply("{\"route\":\"clarify\",\"message\":\"어느 품목의 재고인가요?\"}", 0, 0),
                new LlmReply("{\"route\":\"refuse\",\"message\":\"데이터를 변경할 수 없습니다.\"}", 0, 0));
        ChatService stubbed = serviceWith(scripted);

        ChatResult clarify = stubbed.answer("재고 어때?", PromptVersion.V3);
        ChatResult refuse = stubbed.answer("SKU-007 재고를 0으로 바꿔줘", PromptVersion.V3);

        assertThat(clarify.action()).isEqualTo(Action.CLARIFY);
        assertThat(clarify.route()).isEqualTo(Route.CLARIFY);
        assertThat(clarify.message()).isEqualTo("어느 품목의 재고인가요?");
        assertThat(clarify.attempts()).isEqualTo(1);
        assertThat(refuse.action()).isEqualTo(Action.REFUSE);
        assertThat(refuse.route()).isEqualTo(Route.REFUSE);
        assertThat(refuse.status()).isEqualTo(ChatStatus.OK);
        assertThat(scripted.formats).containsExactly(OutputFormat.ROUTE, OutputFormat.ROUTE);
        verify(executor, never()).execute(any());
        verify(toolExecutor, never()).execute(any());
    }

    @Test
    void v3TurnsMalformedRouterOutputIntoFormatError() {
        ScriptedLlmClient scripted = new ScriptedLlmClient(
                new LlmReply("{\"action\":\"sql\",\"sql\":\"SELECT 1\",\"message\":\"\"}", 0, 0));

        ChatResult result = serviceWith(scripted).answer(QUESTION, PromptVersion.V3);

        assertThat(result.action()).isNull();
        assertThat(result.status()).isEqualTo(ChatStatus.FORMAT_ERROR);
        assertThat(result.message()).contains("unknown route");
        assertThat(result.route()).isNull();
        assertThat(result.attempts()).isEqualTo(1);
        assertThat(savedChatLog().getRoute()).isNull();
    }

    @Test
    void v3ReportsLlmUnavailableFromTheRouterAndFromTheSqlStage() {
        ChatResult routerDown = serviceWith(new StubLlmClient(null)).answer(QUESTION, PromptVersion.V3);

        assertThat(routerDown.status()).isEqualTo(ChatStatus.LLM_UNAVAILABLE);
        assertThat(routerDown.route()).isNull();
        assertThat(routerDown.attempts()).isEqualTo(1);

        LlmClient sqlStageDown = new LlmClient() {
            @Override
            public LlmReply generate(List<LlmMessage> messages, OutputFormat format) {
                if (format == OutputFormat.ROUTE) {
                    return new LlmReply(ROUTE_SQL, 0, 0);
                }
                throw new LlmUnavailableException("Ollama call failed: Read timed out", null);
            }

            @Override
            public String modelName() {
                return "flaky";
            }
        };
        ChatResult sqlDown = serviceWith(sqlStageDown).answer(QUESTION, PromptVersion.V3);

        assertThat(sqlDown.status()).isEqualTo(ChatStatus.LLM_UNAVAILABLE);
        assertThat(sqlDown.action()).isNull();
        assertThat(sqlDown.route()).isEqualTo(Route.SQL);
        assertThat(sqlDown.attempts()).isEqualTo(2);
    }

    @Test
    void v3KeepsARejectedAnswerOutOfReachButLogsIt() {
        QueryResult rows = new QueryResult(List.of("days_of_supply"), List.of(List.<Object>of(12.5)), false);
        when(toolExecutor.execute(any())).thenReturn(rows);
        ScriptedLlmClient scripted = new ScriptedLlmClient(new LlmReply(ROUTE_DAYS_OF_SUPPLY, 0, 0),
                new LlmReply("{\"answer\":\"재고보유일수는 약 13일로, 2주 안에 소진됩니다.\"}", 0, 0));

        ChatResult result = serviceWith(scripted).answer(QUESTION, PromptVersion.V3);

        // 12.5는 13으로 반올림할 수 있지만 2(주)는 결과에 없는 숫자다.
        assertThat(result.status()).isEqualTo(ChatStatus.OK);
        assertThat(result.result()).isEqualTo(rows);
        assertThat(result.answerStatus()).isEqualTo(AnswerStatus.REJECTED);
        assertThat(result.answerText()).isEqualTo("재고보유일수는 약 13일로, 2주 안에 소진됩니다.");
        ChatLog saved = savedChatLog();
        assertThat(saved.getStatus()).isEqualTo("OK");
        assertThat(saved.getAnswerStatus()).isEqualTo("REJECTED");
        assertThat(saved.getAnswerText()).contains("2주 안에 소진");
        assertThat(saved.getErrorMessage()).isNull();
    }

    @Test
    void v3SkipsTheAnswerForEmptyTooLongOrTruncatedResults() {
        QueryResult empty = new QueryResult(List.of("sku"), List.of(), false);
        QueryResult tooLong = new QueryResult(List.of("sku"), java.util.Collections.nCopies(21, List.<Object>of("x")), false);
        QueryResult truncated = new QueryResult(List.of("sku"), java.util.Collections.nCopies(5, List.<Object>of("x")), true);
        QueryResult atLimit = new QueryResult(List.of("sku"), java.util.Collections.nCopies(20, List.<Object>of("x")), false);
        when(toolExecutor.execute(any())).thenReturn(empty, tooLong, truncated, atLimit);
        ScriptedLlmClient scripted = new ScriptedLlmClient(
                new LlmReply(ROUTE_DAYS_OF_SUPPLY, 0, 0), new LlmReply(ROUTE_DAYS_OF_SUPPLY, 0, 0),
                new LlmReply(ROUTE_DAYS_OF_SUPPLY, 0, 0), new LlmReply(ROUTE_DAYS_OF_SUPPLY, 0, 0),
                new LlmReply("{\"answer\":\"해당 품목은 x입니다.\"}", 0, 0));
        ChatService stubbed = serviceWith(scripted);

        for (QueryResult skipped : List.of(empty, tooLong, truncated)) {
            ChatResult result = stubbed.answer(QUESTION, PromptVersion.V3);

            assertThat(result.result()).isEqualTo(skipped);
            assertThat(result.answerStatus()).isEqualTo(AnswerStatus.SKIPPED);
            assertThat(result.answerText()).isNull();
            assertThat(result.attempts()).isEqualTo(1);
        }
        ChatResult answered = stubbed.answer(QUESTION, PromptVersion.V3);

        assertThat(answered.answerStatus()).isEqualTo(AnswerStatus.VERIFIED);
        assertThat(answered.attempts()).isEqualTo(2);
        assertThat(scripted.formats).containsExactly(OutputFormat.ROUTE, OutputFormat.ROUTE, OutputFormat.ROUTE,
                OutputFormat.ROUTE, OutputFormat.ANSWER);
    }

    @Test
    void v3KeepsTheQueryResultWhenTheAnswerCallFailsOrIsMalformed() {
        QueryResult rows = new QueryResult(List.of("days_of_supply"), List.of(List.<Object>of(12.5)), false);
        when(toolExecutor.execute(any())).thenReturn(rows);
        LlmClient answerDown = new LlmClient() {
            @Override
            public LlmReply generate(List<LlmMessage> messages, OutputFormat format) {
                if (format == OutputFormat.ANSWER) {
                    throw new LlmUnavailableException("Ollama call failed: Read timed out", null);
                }
                return new LlmReply(ROUTE_DAYS_OF_SUPPLY, 0, 0);
            }

            @Override
            public String modelName() {
                return "flaky";
            }
        };

        ChatResult down = serviceWith(answerDown).answer(QUESTION, PromptVersion.V3);
        ChatResult malformed = serviceWith(new ScriptedLlmClient(new LlmReply(ROUTE_DAYS_OF_SUPPLY, 0, 0),
                new LlmReply("재고보유일수는 12.5일입니다.", 0, 0))).answer(QUESTION, PromptVersion.V3);
        ChatResult blank = serviceWith(new ScriptedLlmClient(new LlmReply(ROUTE_DAYS_OF_SUPPLY, 0, 0),
                new LlmReply("{\"answer\":\"\"}", 0, 0))).answer(QUESTION, PromptVersion.V3);

        for (ChatResult result : List.of(down, malformed, blank)) {
            assertThat(result.status()).isEqualTo(ChatStatus.OK);
            assertThat(result.action()).isEqualTo(Action.TOOL);
            assertThat(result.result()).isEqualTo(rows);
            assertThat(result.answerStatus()).isEqualTo(AnswerStatus.SKIPPED);
            assertThat(result.answerText()).isNull();
            assertThat(result.attempts()).isEqualTo(2);
        }
    }

    @Test
    void v3HasNoAnswerStatusWithoutAQueryResult() {
        ScriptedLlmClient scripted = new ScriptedLlmClient(
                new LlmReply("{\"route\":\"clarify\",\"message\":\"어느 품목의 재고인가요?\"}", 0, 0),
                new LlmReply("{\"route\":\"tool\",\"tool\":\"days_of_supply\",\"location\":\"서울\",\"output\":\"value\"}", 0, 0));
        ChatService stubbed = serviceWith(scripted);

        assertThat(stubbed.answer("재고 어때?", PromptVersion.V3).answerStatus()).isNull();
        assertThat(stubbed.answer("서울 재고보유일수는?", PromptVersion.V3).answerStatus()).isNull();
        assertThat(scripted.formats).containsExactly(OutputFormat.ROUTE, OutputFormat.ROUTE);
    }

    @Test
    void v0ToV2NeverWriteAnAnswer() {
        when(executor.execute("SELECT on_hand FROM inventory_daily"))
                .thenReturn(new QueryResult(List.of("on_hand"), List.of(List.of(120)), false));
        ScriptedLlmClient scripted = new ScriptedLlmClient(
                new LlmReply("{\"action\":\"sql\",\"sql\":\"SELECT on_hand FROM inventory_daily\",\"message\":\"\"}", 0, 0));

        ChatResult result = serviceWith(scripted).answer(QUESTION, PromptVersion.V1);

        assertThat(result.answerStatus()).isNull();
        assertThat(result.answerText()).isNull();
        assertThat(result.attempts()).isEqualTo(1);
        assertThat(scripted.formats).containsExactly(OutputFormat.ACTION);
        assertThat(savedChatLog().getAnswerStatus()).isNull();
    }

    private ChatLog savedChatLog() {
        ArgumentCaptor<ChatLog> captor = ArgumentCaptor.forClass(ChatLog.class);
        verify(chatLogRepository, times(1)).save(captor.capture());
        return captor.getValue();
    }

    private static ChatProperties properties() {
        ChatProperties.Llm llm = new ChatProperties.Llm(
                "fake", "http://localhost:11434", "qwen2.5-coder:7b", 0, 42, 8192, 120);
        // Gradle은 backend/를 작업 디렉터리로 테스트를 실행한다.
        return new ChatProperties(
                "v0", LocalDate.of(2026, 11, 8), LocalDate.of(2026, 8, 10), 1000, 200, 20, "../prompts", llm);
    }

    private ChatService serviceWith(LlmClient llmClient) {
        ChatProperties properties = properties();
        return new ChatService(new PromptBuilder(properties), llmClient, new LlmOutputParser(),
                new SqlValidator(properties), executor, new ToolArgsValidator(DIMENSIONS, properties), toolExecutor,
                new NumberVerifier(), chatLogRepository, properties);
    }

    /** 정해 둔 응답을 순서대로 돌려주고 받은 메시지를 기록한다. */
    private static final class ScriptedLlmClient implements LlmClient {

        private final java.util.ArrayDeque<LlmReply> replies;
        private final List<List<LlmMessage>> requests = new java.util.ArrayList<>();
        private final List<OutputFormat> formats = new java.util.ArrayList<>();

        private ScriptedLlmClient(LlmReply... replies) {
            this.replies = new java.util.ArrayDeque<>(List.of(replies));
        }

        @Override
        public LlmReply generate(List<LlmMessage> messages, OutputFormat format) {
            requests.add(messages);
            formats.add(format);
            return replies.remove();
        }

        @Override
        public String modelName() {
            return "scripted";
        }
    }

    /** reply가 null이면 연결 실패를 흉내 낸다. */
    private record StubLlmClient(LlmReply reply) implements LlmClient {

        @Override
        public LlmReply generate(List<LlmMessage> messages, OutputFormat format) {
            if (reply == null) {
                throw new LlmUnavailableException("Ollama call failed: Connection refused", null);
            }
            return reply;
        }

        @Override
        public String modelName() {
            return "stub-model";
        }
    }
}
