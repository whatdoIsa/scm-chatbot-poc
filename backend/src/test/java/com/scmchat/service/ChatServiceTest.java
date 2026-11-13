package com.scmchat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import com.scmchat.prompt.PromptBuilder;
import com.scmchat.prompt.PromptVersion;
import com.scmchat.repository.ChatLogRepository;
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

    private final FakeLlmClient fakeLlm = new FakeLlmClient();
    private final ReadOnlySqlExecutor executor = mock(ReadOnlySqlExecutor.class);
    private final ChatLogRepository chatLogRepository = mock(ChatLogRepository.class);
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

    private ChatLog savedChatLog() {
        ArgumentCaptor<ChatLog> captor = ArgumentCaptor.forClass(ChatLog.class);
        verify(chatLogRepository, times(1)).save(captor.capture());
        return captor.getValue();
    }

    private ChatService serviceWith(LlmClient llmClient) {
        ChatProperties.Llm llm = new ChatProperties.Llm(
                "fake", "http://localhost:11434", "qwen2.5-coder:7b", 0, 42, 8192, 120);
        // Gradle은 backend/를 작업 디렉터리로 테스트를 실행한다.
        ChatProperties properties = new ChatProperties("v0", LocalDate.of(2026, 11, 8), LocalDate.of(2026, 8, 10), 1000, 200, "../prompts", llm);
        return new ChatService(new PromptBuilder(properties), llmClient, new LlmOutputParser(),
                new SqlValidator(properties), executor, chatLogRepository, properties);
    }

    /** 정해 둔 응답을 순서대로 돌려주고 받은 메시지를 기록한다. */
    private static final class ScriptedLlmClient implements LlmClient {

        private final java.util.ArrayDeque<LlmReply> replies;
        private final List<List<LlmMessage>> requests = new java.util.ArrayList<>();

        private ScriptedLlmClient(LlmReply... replies) {
            this.replies = new java.util.ArrayDeque<>(List.of(replies));
        }

        @Override
        public LlmReply generate(List<LlmMessage> messages) {
            requests.add(messages);
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
        public LlmReply generate(List<LlmMessage> messages) {
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
