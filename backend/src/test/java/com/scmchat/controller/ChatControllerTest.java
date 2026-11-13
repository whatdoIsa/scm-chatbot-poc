package com.scmchat.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scmchat.config.ChatProperties;
import com.scmchat.executor.QueryResult;
import com.scmchat.llm.Action;
import com.scmchat.prompt.PromptVersion;
import com.scmchat.service.ChatResult;
import com.scmchat.service.ChatService;
import com.scmchat.service.ChatStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@WebMvcTest(ChatController.class)
@EnableConfigurationProperties(ChatProperties.class)
class ChatControllerTest {

    private static final String INVALID_QUESTION =
            "{\"code\":\"INVALID_REQUEST\",\"message\":\"question must be 1..500 characters\"}";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private ChatService chatService;

    @Test
    void returnsSqlResultInApiSpecFormat() throws Exception {
        QueryResult rows = new QueryResult(
                List.of("sale_date", "qty", "unit_cost", "note"),
                List.of(Arrays.asList(LocalDate.of(2026, 11, 8), 120, new BigDecimal("1500.50"), null)),
                false);
        when(chatService.answer(any(), any())).thenReturn(new ChatResult(
                Action.SQL, ChatStatus.OK, null, "SELECT 1", rows, PromptVersion.V1, "qwen2.5-coder:7b",
                1, 512, 37, 6120, 41L));

        postQuestion("SKU-007의 현재 재고는?")
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {
                          "action": "sql", "status": "OK", "message": null, "sql": "SELECT 1",
                          "columns": ["sale_date", "qty", "unit_cost", "note"],
                          "rows": [["2026-11-08", 120, 1500.50, null]],
                          "rowCount": 1, "truncated": false,
                          "asOfDate": "2026-11-08", "promptVersion": "v1", "model": "qwen2.5-coder:7b",
                          "attempts": 1, "elapsedMs": 6120, "chatLogId": 41,
                          "route": null, "tool": null, "answer": null, "answerStatus": null
                        }
                        """, JsonCompareMode.STRICT));
    }

    @Test
    void returnsClarifyWithNullResultFields() throws Exception {
        when(chatService.answer(any(), any())).thenReturn(new ChatResult(
                Action.CLARIFY, ChatStatus.OK, "어느 품목·거점·기간의 재고를 확인할까요?", null, null,
                PromptVersion.V1, "qwen2.5-coder:7b", 1, 500, 20, 2310, 42L));

        postQuestion("재고 어때?")
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {
                          "action": "clarify", "status": "OK", "message": "어느 품목·거점·기간의 재고를 확인할까요?",
                          "sql": null, "columns": null, "rows": null, "rowCount": null, "truncated": false,
                          "asOfDate": "2026-11-08", "promptVersion": "v1", "model": "qwen2.5-coder:7b",
                          "attempts": 1, "elapsedMs": 2310, "chatLogId": 42,
                          "route": null, "tool": null, "answer": null, "answerStatus": null
                        }
                        """, JsonCompareMode.STRICT));
    }

    @Test
    void returnsToolResultWithRouteAndToolCall() throws Exception {
        QueryResult rows = new QueryResult(List.of("days_of_supply"),
                List.of(List.<Object>of(new BigDecimal("12.4907"))), false);
        java.util.Map<String, Object> args = new java.util.LinkedHashMap<>();
        args.put("sku", "SKU-026");
        args.put("location", "서울센터");
        args.put("output", "value");
        when(chatService.answer(any(), any())).thenReturn(new ChatResult(
                Action.TOOL, ChatStatus.OK, null, null, rows, PromptVersion.V3, "qwen2.5-coder:7b",
                1, 900, 40, 3100, 77L, com.scmchat.llm.Route.TOOL, "days_of_supply", args,
                "서울센터 SKU-026의 재고보유일수는 약 12.5일입니다.", com.scmchat.answer.AnswerStatus.VERIFIED));

        postQuestion("서울센터 SKU-026의 재고보유일수는?")
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {
                          "action": "tool", "status": "OK", "message": null, "sql": null,
                          "columns": ["days_of_supply"],
                          "rows": [[12.4907]],
                          "rowCount": 1, "truncated": false,
                          "asOfDate": "2026-11-08", "promptVersion": "v3", "model": "qwen2.5-coder:7b",
                          "attempts": 1, "elapsedMs": 3100, "chatLogId": 77,
                          "route": "tool",
                          "tool": {"name": "days_of_supply",
                                   "args": {"sku": "SKU-026", "location": "서울센터", "output": "value"}},
                          "answer": "서울센터 SKU-026의 재고보유일수는 약 12.5일입니다.", "answerStatus": "VERIFIED"
                        }
                        """, JsonCompareMode.STRICT));
    }

    @Test
    void hidesAnAnswerThatFailedNumberVerification() throws Exception {
        QueryResult rows = new QueryResult(List.of("on_hand"), List.of(List.<Object>of(284)), false);
        when(chatService.answer(any(), any())).thenReturn(new ChatResult(
                Action.SQL, ChatStatus.OK, null, "SELECT 1", rows, PromptVersion.V3, "qwen2.5-coder:7b",
                3, 4000, 90, 9000, 80L, com.scmchat.llm.Route.SQL, null, null,
                "SKU-007의 현재 재고는 300개입니다.", com.scmchat.answer.AnswerStatus.REJECTED));

        postQuestion("SKU-007의 현재 재고는?")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0][0]").value(284))
                .andExpect(jsonPath("$.answer").doesNotExist())
                .andExpect(jsonPath("$.answerStatus").value("REJECTED"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("300개"))));
    }

    @Test
    void returnsRejectedToolCallWithTheReason() throws Exception {
        when(chatService.answer(any(), any())).thenReturn(new ChatResult(
                Action.TOOL, ChatStatus.TOOL_REJECTED, "unknown location: 서울", null, null, PromptVersion.V3,
                "qwen2.5-coder:7b", 1, 900, 40, 2500, 78L, com.scmchat.llm.Route.TOOL, "days_of_supply",
                java.util.Map.of("location", "서울"), null, null));

        postQuestion("서울 재고보유일수는?")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.action").value("tool"))
                .andExpect(jsonPath("$.status").value("TOOL_REJECTED"))
                .andExpect(jsonPath("$.message").value("unknown location: 서울"))
                .andExpect(jsonPath("$.route").value("tool"))
                .andExpect(jsonPath("$.tool.name").value("days_of_supply"))
                .andExpect(jsonPath("$.tool.args.location").value("서울"))
                .andExpect(jsonPath("$.rows").doesNotExist());
    }

    @Test
    void returnsToolObjectForARejectedCallEvenWithoutAToolName() throws Exception {
        when(chatService.answer(any(), any())).thenReturn(new ChatResult(
                Action.TOOL, ChatStatus.TOOL_REJECTED, "unknown tool: null", null, null, PromptVersion.V3,
                "qwen2.5-coder:7b", 1, 900, 40, 2500, 79L, com.scmchat.llm.Route.TOOL, null, java.util.Map.of(), null, null));

        postQuestion("그 지표 알려줘")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.route").value("tool"))
                .andExpect(jsonPath("$.tool.name").doesNotExist())
                .andExpect(jsonPath("$.tool.args").isMap());
    }

    @ParameterizedTest
    @CsvSource({
            "OK, 200",
            "SQL_REJECTED, 422",
            "SQL_EXECUTION_ERROR, 422",
            "TOOL_REJECTED, 422",
            "FORMAT_ERROR, 422",
            "LLM_UNAVAILABLE, 502"
    })
    void mapsStatusToHttpCode(ChatStatus chatStatus, int httpCode) throws Exception {
        when(chatService.answer(any(), any())).thenReturn(new ChatResult(
                null, chatStatus, "reason", null, null, PromptVersion.V1, "qwen2.5-coder:7b", 1, 0, 0, 10, 1L));

        postQuestion("질문")
                .andExpect(status().is(httpCode))
                .andExpect(jsonPath("$.status").value(chatStatus.name()))
                .andExpect(jsonPath("$.chatLogId").value(1));
    }

    @Test
    void passesTrimmedQuestionAndConfiguredVersionToService() throws Exception {
        when(chatService.answer(any(), any())).thenReturn(new ChatResult(
                Action.REFUSE, ChatStatus.OK, "거부", null, null, PromptVersion.V1, "m", 1, 0, 0, 1, 1L));

        postQuestion("  SKU-007 재고를 0으로 바꿔줘 \n").andExpect(status().isOk());

        verify(chatService).answer("SKU-007 재고를 0으로 바꿔줘", PromptVersion.V1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"question\":\"\"}", "{\"question\":\"   \"}", "{\"question\":null}", "{}"})
    void rejectsEmptyQuestion(String body) throws Exception {
        mockMvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().json(INVALID_QUESTION, JsonCompareMode.STRICT));
        verifyNoInteractions(chatService);
    }

    @Test
    void rejectsQuestionLongerThan500Characters() throws Exception {
        postQuestion("가".repeat(501))
                .andExpect(status().isBadRequest())
                .andExpect(content().json(INVALID_QUESTION, JsonCompareMode.STRICT));
        verifyNoInteractions(chatService);
    }

    @Test
    void acceptsQuestionOfExactly500Characters() throws Exception {
        when(chatService.answer(any(), any())).thenReturn(new ChatResult(
                Action.REFUSE, ChatStatus.OK, "거부", null, null, PromptVersion.V1, "m", 1, 0, 0, 1, 1L));

        postQuestion("가".repeat(500)).andExpect(status().isOk());
    }

    @Test
    void measuresLengthWithoutSurroundingWhitespace() throws Exception {
        when(chatService.answer(any(), any())).thenReturn(new ChatResult(
                Action.REFUSE, ChatStatus.OK, "거부", null, null, PromptVersion.V1, "m", 1, 0, 0, 1, 1L));

        postQuestion("  " + "가".repeat(500) + " \n").andExpect(status().isOk());

        verify(chatService).answer("가".repeat(500), PromptVersion.V1);
    }

    @Test
    void rejectsMalformedJson() throws Exception {
        mockMvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON).content("{question:"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void hidesUnexpectedErrorDetails() throws Exception {
        when(chatService.answer(any(), any())).thenThrow(new IllegalStateException("db password is hunter2"));

        postQuestion("질문")
                .andExpect(status().isInternalServerError())
                .andExpect(content().json(
                        "{\"code\":\"INTERNAL_ERROR\",\"message\":\"unexpected server error\"}",
                        JsonCompareMode.STRICT));
    }

    @Test
    void keepsMethodNotAllowedForGet() throws Exception {
        mockMvc.perform(get("/api/chat"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }

    private ResultActions postQuestion(String question) throws Exception {
        return mockMvc.perform(post("/api/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("question", question))));
    }
}
