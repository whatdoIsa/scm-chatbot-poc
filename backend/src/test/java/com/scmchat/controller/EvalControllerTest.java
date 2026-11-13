package com.scmchat.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.scmchat.dto.EvalSummaryResponse;
import com.scmchat.service.EvalSummaryService;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(EvalController.class)
class EvalControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private EvalSummaryService evalSummaryService;

    @Test
    void returnsSummaryInApiSpecFormat() throws Exception {
        when(evalSummaryService.summarize(null)).thenReturn(new EvalSummaryResponse(List.of(
                new EvalSummaryResponse.Run(3, "v0", "qwen2.5-coder:7b", new BigDecimal("0.00"), 42, 1, 30, 12,
                        new BigDecimal("0.4000"), new BigDecimal("0.8000"), new BigDecimal("1.0000"), 8200,
                        List.of(new EvalSummaryResponse.TypeAccuracy("SIMPLE_LOOKUP", 6, 4, new BigDecimal("0.6667"))),
                        List.of(new EvalSummaryResponse.ErrorTypeCount("DATE_RANGE", 3),
                                new EvalSummaryResponse.ErrorTypeCount("UNCLASSIFIED", 2)),
                        null, List.of()),
                new EvalSummaryResponse.Run(9, "v3", "qwen2.5-coder:7b", new BigDecimal("0.00"), 42, 1, 30, 24,
                        new BigDecimal("0.8000"), new BigDecimal("0.9000"), new BigDecimal("1.0000"), 6100,
                        List.of(), List.of(), new BigDecimal("0.9000"),
                        List.of(new EvalSummaryResponse.RouteAccuracy("tool", 9, 8),
                                new EvalSummaryResponse.RouteAccuracy("sql", 15, 10))))));

        mockMvc.perform(get("/api/eval/summary"))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"runs": [{
                          "runId": 3, "promptVersion": "v0", "model": "qwen2.5-coder:7b",
                          "temperature": 0.0, "seed": 42, "repeatNo": 1,
                          "total": 30, "correct": 12, "accuracy": 0.4,
                          "sqlExecSuccessRate": 0.8, "formatOkRate": 1.0, "avgElapsedMs": 8200,
                          "byType": [{"type": "SIMPLE_LOOKUP", "total": 6, "correct": 4, "accuracy": 0.6667}],
                          "errorTypes": [{"errorType": "DATE_RANGE", "count": 3}, {"errorType": "UNCLASSIFIED", "count": 2}],
                          "answerVerifiedRate": null, "routes": []
                        }, {
                          "runId": 9, "promptVersion": "v3", "model": "qwen2.5-coder:7b",
                          "temperature": 0.0, "seed": 42, "repeatNo": 1,
                          "total": 30, "correct": 24, "accuracy": 0.8,
                          "sqlExecSuccessRate": 0.9, "formatOkRate": 1.0, "avgElapsedMs": 6100,
                          "byType": [], "errorTypes": [],
                          "answerVerifiedRate": 0.9,
                          "routes": [{"route": "tool", "total": 9, "correct": 8}, {"route": "sql", "total": 15, "correct": 10}]
                        }]}
                        """, JsonCompareMode.STRICT));
    }

    @Test
    void passesModelFilterToService() throws Exception {
        when(evalSummaryService.summarize("fake")).thenReturn(new EvalSummaryResponse(List.of()));

        mockMvc.perform(get("/api/eval/summary").param("model", "fake"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"runs\": []}", JsonCompareMode.STRICT));

        verify(evalSummaryService).summarize("fake");
    }
}
