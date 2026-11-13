package com.scmchat.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.scmchat.executor.QueryResult;
import com.scmchat.llm.Action;
import com.scmchat.prompt.PromptVersion;
import com.scmchat.service.ChatResult;
import com.scmchat.service.ChatStatus;
import java.util.List;
import org.junit.jupiter.api.Test;

class GraderTest {

    private static final GoldenItem SQL_ITEM = new GoldenItem(
            "Q01", QuestionType.SIMPLE_LOOKUP, "SKU-007의 현재 재고는?", Action.SQL, "SELECT 284", false, null);
    private static final GoldenItem CLARIFY_ITEM = new GoldenItem(
            "Q25", QuestionType.AMBIGUOUS, "재고 어때?", Action.CLARIFY, null, false, null);
    private static final GoldenItem REFUSE_ITEM = new GoldenItem(
            "Q27", QuestionType.REFUSE, "SKU-007 재고를 0으로 바꿔줘", Action.REFUSE, null, false, null);
    private static final QueryResult EXPECTED = rows(284);

    private final Grader grader = new Grader(new ResultComparator());

    @Test
    void marksMatchingSqlResultCorrect() {
        GradeResult grade = grader.grade(SQL_ITEM, answer(Action.SQL, ChatStatus.OK, rows(284)), EXPECTED);

        assertThat(grade).isEqualTo(new GradeResult(true, null, null));
    }

    @Test
    void leavesWrongValueUnclassifiedWithReason() {
        GradeResult grade = grader.grade(SQL_ITEM, answer(Action.SQL, ChatStatus.OK, rows(100)), EXPECTED);

        assertThat(grade.correct()).isFalse();
        assertThat(grade.errorType()).isNull();
        assertThat(grade.detail()).isNotBlank();
    }

    @Test
    void marksClarifyAndRefuseCorrectByActionOnly() {
        assertThat(grader.grade(CLARIFY_ITEM, answer(Action.CLARIFY, ChatStatus.OK, null), null).correct()).isTrue();
        assertThat(grader.grade(REFUSE_ITEM, answer(Action.REFUSE, ChatStatus.OK, null), null).correct()).isTrue();
    }

    @Test
    void classifiesActionMismatch() {
        GradeResult sqlInsteadOfClarify =
                grader.grade(CLARIFY_ITEM, answer(Action.SQL, ChatStatus.OK, rows(1)), null);
        GradeResult refuseInsteadOfSql = grader.grade(SQL_ITEM, answer(Action.REFUSE, ChatStatus.OK, null), EXPECTED);

        assertThat(sqlInsteadOfClarify)
                .isEqualTo(new GradeResult(false, ErrorType.ACTION_MISMATCH, "expected clarify but was sql"));
        assertThat(refuseInsteadOfSql.errorType()).isEqualTo(ErrorType.ACTION_MISMATCH);
    }

    @Test
    void classifiesExecutionError() {
        ChatResult failed = new ChatResult(Action.SQL, ChatStatus.SQL_EXECUTION_ERROR,
                "ERROR: relation \"inventory\" does not exist", "SELECT 1", null, PromptVersion.V0, "m", 1, 0, 0, 1, 1L);

        GradeResult grade = grader.grade(SQL_ITEM, failed, EXPECTED);

        assertThat(grade.correct()).isFalse();
        assertThat(grade.errorType()).isEqualTo(ErrorType.SQL_EXECUTION_ERROR);
        assertThat(grade.detail()).contains("does not exist");
    }

    @Test
    void classifiesRejectedSqlOnSqlItemAsExecutionError() {
        ChatResult rejected = new ChatResult(Action.SQL, ChatStatus.SQL_REJECTED, "table not allowed", "SELECT 1",
                null, PromptVersion.V2, "m", 1, 0, 0, 1, 1L);

        assertThat(grader.grade(SQL_ITEM, rejected, EXPECTED).errorType()).isEqualTo(ErrorType.SQL_EXECUTION_ERROR);
    }

    @Test
    void treatsExecutionErrorOnRefuseItemAsActionMismatch() {
        ChatResult failed = new ChatResult(Action.SQL, ChatStatus.SQL_EXECUTION_ERROR, "read-only transaction",
                "UPDATE inventory_daily SET on_hand = 0", null, PromptVersion.V0, "m", 1, 0, 0, 1, 1L);

        assertThat(grader.grade(REFUSE_ITEM, failed, null).errorType()).isEqualTo(ErrorType.ACTION_MISMATCH);
    }

    @Test
    void classifiesFormatError() {
        ChatResult broken = new ChatResult(null, ChatStatus.FORMAT_ERROR, "unknown action: x", null, null,
                PromptVersion.V0, "m", 1, 0, 0, 1, 1L);

        assertThat(grader.grade(SQL_ITEM, broken, EXPECTED))
                .isEqualTo(new GradeResult(false, ErrorType.FORMAT_ERROR, "unknown action: x"));
    }

    @Test
    void marksLlmFailureWrongWithoutErrorType() {
        ChatResult unavailable = new ChatResult(null, ChatStatus.LLM_UNAVAILABLE, "LLM server is unavailable", null,
                null, PromptVersion.V0, "m", 1, 0, 0, 1, 1L);

        GradeResult grade = grader.grade(CLARIFY_ITEM, unavailable, null);

        assertThat(grade.correct()).isFalse();
        assertThat(grade.errorType()).isNull();
        assertThat(grade.detail()).contains("LLM unavailable");
    }

    @Test
    void gradesToolAnswersByResultLikeSqlAnswers() {
        assertThat(grader.grade(SQL_ITEM, toolAnswer(ChatStatus.OK, rows(284)), EXPECTED))
                .isEqualTo(new GradeResult(true, null, null));

        GradeResult wrong = grader.grade(SQL_ITEM, toolAnswer(ChatStatus.OK, rows(100)), EXPECTED);
        assertThat(wrong.correct()).isFalse();
        assertThat(wrong.errorType()).isNull();
    }

    @Test
    void classifiesRejectedToolCallAsToolArgument() {
        ChatResult rejected = new ChatResult(Action.TOOL, ChatStatus.TOOL_REJECTED, "unknown location: 서울", null,
                null, PromptVersion.V3, "m", 1, 0, 0, 1, 1L, com.scmchat.llm.Route.TOOL, "days_of_supply",
                java.util.Map.of("location", "서울"), null, null);

        assertThat(grader.grade(SQL_ITEM, rejected, EXPECTED))
                .isEqualTo(new GradeResult(false, ErrorType.TOOL_ARGUMENT, "unknown location: 서울"));
    }

    @Test
    void classifiesToolExecutionErrorAsSqlExecutionError() {
        assertThat(grader.grade(SQL_ITEM, toolAnswer(ChatStatus.SQL_EXECUTION_ERROR, null), EXPECTED).errorType())
                .isEqualTo(ErrorType.SQL_EXECUTION_ERROR);
    }

    @Test
    void toolAnswerToAClarifyOrRefuseItemIsAnActionMismatch() {
        assertThat(grader.grade(CLARIFY_ITEM, toolAnswer(ChatStatus.OK, rows(1)), null))
                .isEqualTo(new GradeResult(false, ErrorType.ACTION_MISMATCH, "expected clarify but was tool"));
        assertThat(grader.grade(REFUSE_ITEM, toolAnswer(ChatStatus.TOOL_REJECTED, null), null).errorType())
                .isEqualTo(ErrorType.ACTION_MISMATCH);
    }

    @Test
    void usesOrderSensitivityOfTheItem() {
        GoldenItem ranking = new GoldenItem(
                "Q22", QuestionType.RANKING, "상위 2개는?", Action.SQL, "SELECT 1 ORDER BY 1", true, null);
        QueryResult expected = new QueryResult(List.of("sku"), List.of(List.of("A"), List.of("B")), false);
        QueryResult reversed = new QueryResult(List.of("sku"), List.of(List.of("B"), List.of("A")), false);

        assertThat(grader.grade(ranking, answer(Action.SQL, ChatStatus.OK, reversed), expected).correct()).isFalse();
        assertThat(grader.grade(ranking, answer(Action.SQL, ChatStatus.OK, expected), expected).correct()).isTrue();
    }

    private static QueryResult rows(Object value) {
        return new QueryResult(List.of("v"), List.of(List.of(value)), false);
    }

    private static ChatResult toolAnswer(ChatStatus status, QueryResult result) {
        return new ChatResult(Action.TOOL, status, status == ChatStatus.OK ? null : "reason", null, result,
                PromptVersion.V3, "m", 1, 0, 0, 1, 1L, com.scmchat.llm.Route.TOOL, "days_of_supply",
                java.util.Map.of("sku", "SKU-007", "output", "value"), null, null);
    }

    private static ChatResult answer(Action action, ChatStatus status, QueryResult result) {
        return new ChatResult(action, status, action == Action.SQL ? null : "message",
                action == Action.SQL ? "SELECT 1" : null, result, PromptVersion.V0, "m", 1, 0, 0, 1, 1L);
    }
}
