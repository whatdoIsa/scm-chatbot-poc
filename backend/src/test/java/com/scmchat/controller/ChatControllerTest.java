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
                          "attempts": 1, "elapsedMs": 6120, "chatLogId": 41
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
                          "attempts": 1, "elapsedMs": 2310, "chatLogId": 42
                        }
                        """, JsonCompareMode.STRICT));
    }

    @ParameterizedTest
    @CsvSource({
            "OK, 200",
            "SQL_REJECTED, 422",
            "SQL_EXECUTION_ERROR, 422",
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
