package com.scmchat.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.scmchat.config.EvalProperties;
import com.scmchat.entity.EvalResult;
import com.scmchat.entity.EvalRun;
import com.scmchat.llm.Action;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class EvalReportWriterTest {

    private static final List<GoldenItem> ITEMS = List.of(
            new GoldenItem("Q15", QuestionType.METRIC, "서울센터 SKU-026의 재고보유일수는?", Action.SQL, "SELECT 1", false, null),
            new GoldenItem("Q20", QuestionType.METRIC, "최근 28일 SKU-029의 일평균 판매량은?", Action.SQL, "SELECT 1", false, null),
            new GoldenItem("Q25", QuestionType.AMBIGUOUS, "재고 어때?", Action.CLARIFY, null, false, null));

    private final EvalReportWriter writer =
            new EvalReportWriter(new EvalProperties("unused", "unused", "build/eval-reports", List.of("v3"), 1, null));

    @Test
    void v3ReportHasRouteAccuracyAnswerVerificationAndToolCalls() {
        EvalRun run = run("v3", 3, 1);
        run.recordAnswerVerifiedRate(new BigDecimal("0.5000"));
        EvalResult toolCorrect = result(run, "Q15", "METRIC", "sql", "tool", null, true, null, 2);
        toolCorrect.recordRoute("tool", "days_of_supply", "{\"sku\":\"SKU-026\",\"output\":\"value\"}");
        toolCorrect.recordAnswer("재고보유일수는 12.5일입니다.", "VERIFIED");
        EvalResult toolWrong = result(run, "Q20", "METRIC", "sql", "tool", null, false, null, 2);
        toolWrong.recordRoute("tool", "avg_daily_sales", "{\"sku\":\"SKU-029\",\"days\":7,\"output\":\"value\"}");
        toolWrong.recordAnswer("일평균 판매량은 약 40개로 | 2주치입니다.", "REJECTED");
        EvalResult routerFailed = result(run, "Q25", "AMBIGUOUS", "clarify", null, null, false, "FORMAT_ERROR", 1);

        String report = writer.render(run, List.of(toolCorrect, toolWrong, routerFailed), ITEMS);

        assertThat(report).contains("| 평균 LLM 호출 수 | 1.6667 |", "| 숫자 검증 통과율 | 0.5000 |");
        assertThat(report).contains("## 경로별 정확도", "| tool | 2 | 1 | 0.5000 |", "| none | 1 | 0 | 0.0000 |");
        assertThat(report).doesNotContain("| sql | 0 |");
        assertThat(report).contains("## 답변 문장 후검증", "| VERIFIED | 1 |", "| REJECTED | 1 |", "| SKIPPED | 0 |",
                "| 조회 결과 없음 | 1 |");
        assertThat(report).contains("| Q20 | 최근 28일 SKU-029의 일평균 판매량은? | 일평균 판매량은 약 40개로 \\| 2주치입니다. | 오답 |");
        assertThat(report).contains("tool: avg_daily_sales {\"sku\":\"SKU-029\",\"days\":7,\"output\":\"value\"}");
    }

    @Test
    void reportOfAnUnroutedRunKeepsTheOriginalSections() {
        EvalRun run = run("v1", 2, 1);
        EvalResult correct = result(run, "Q15", "METRIC", "sql", "sql", "SELECT 1", true, null, 1);
        EvalResult wrong = result(run, "Q20", "METRIC", "sql", "sql", "SELECT AVG(qty) FROM sales_daily", false,
                "METRIC_DEFINITION", 1);

        String report = writer.render(run, List.of(correct, wrong), ITEMS);

        assertThat(report).contains("## 유형별 정확도", "## 오답 원인별 건수", "## 오답 목록",
                "| METRIC_DEFINITION | 1 |", "SELECT AVG(qty) FROM sales_daily");
        assertThat(report).doesNotContain("경로별 정확도", "답변 문장 후검증", "평균 LLM 호출 수", "숫자 검증 통과율");
    }

    private static EvalRun run(String version, int total, int correct) {
        EvalRun run = new EvalRun(version, "qwen2.5-coder:7b", new BigDecimal("0.00"), 42, 1, "a".repeat(64),
                OffsetDateTime.now());
        ReflectionTestUtils.setField(run, "id", 99L);
        run.complete(OffsetDateTime.now(), total, correct,
                BigDecimal.valueOf(correct).divide(BigDecimal.valueOf(total), 4, java.math.RoundingMode.HALF_UP),
                new BigDecimal("1.0000"), new BigDecimal("1.0000"), 5000);
        return run;
    }

    private static EvalResult result(EvalRun run, String id, String type, String expected, String actual, String sql,
            boolean correct, String errorType, int attempts) {
        return new EvalResult(run, id, type, expected, actual, sql, correct, errorType,
                "FORMAT_ERROR".equals(errorType) ? "FORMAT_ERROR" : "OK", attempts, 1000, 50, 5000, null);
    }
}
