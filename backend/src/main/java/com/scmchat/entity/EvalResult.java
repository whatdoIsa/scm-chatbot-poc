package com.scmchat.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "eval_result", uniqueConstraints = @UniqueConstraint(columnNames = {"run_id", "question_id"}))
public class EvalResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "run_id", nullable = false)
    private EvalRun run;

    @Column(name = "question_id", nullable = false, length = 8)
    private String questionId;

    @Column(nullable = false, length = 16)
    private String qtype;

    @Column(name = "expected_action", nullable = false, length = 16)
    private String expectedAction;

    @Column(name = "actual_action", length = 16)
    private String actualAction;

    @Column(name = "generated_sql", columnDefinition = "text")
    private String generatedSql;

    @Column(nullable = false)
    private boolean correct;

    @Column(name = "error_type", length = 32)
    private String errorType;

    @Column(nullable = false, length = 32)
    private String status;

    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(nullable = false)
    private int attempts;

    @Column(name = "prompt_tokens", nullable = false)
    private int promptTokens;

    @Column(name = "completion_tokens", nullable = false)
    private int completionTokens;

    @Column(name = "elapsed_ms", nullable = false)
    private int elapsedMs;

    @Column(columnDefinition = "text")
    private String detail;

    @Column(length = 16)
    private String route;

    @Column(name = "tool_name", length = 32)
    private String toolName;

    @Column(name = "tool_args", columnDefinition = "text")
    private String toolArgs;

    @Column(name = "answer_text", columnDefinition = "text")
    private String answerText;

    @Column(name = "answer_status", length = 16)
    private String answerStatus;

    protected EvalResult() {
    }

    public EvalResult(EvalRun run, String questionId, String qtype, String expectedAction, String actualAction,
            String generatedSql, boolean correct, String errorType, String status, int attempts,
            int promptTokens, int completionTokens, int elapsedMs, String detail) {
        this.run = run;
        this.questionId = questionId;
        this.qtype = qtype;
        this.expectedAction = expectedAction;
        this.actualAction = actualAction;
        this.generatedSql = generatedSql;
        this.correct = correct;
        this.errorType = errorType;
        this.status = status;
        this.attempts = attempts;
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        this.elapsedMs = elapsedMs;
        this.detail = detail;
    }

    /** v3 라우터가 고른 경로와 도구 호출(인자는 JSON 문자열). v0~v2에서는 부르지 않아 NULL로 남는다. */
    public void recordRoute(String route, String toolName, String toolArgs) {
        this.route = route;
        this.toolName = toolName;
        this.toolArgs = toolArgs;
    }

    public String getRoute() {
        return route;
    }

    public String getToolName() {
        return toolName;
    }

    public String getToolArgs() {
        return toolArgs;
    }

    /** v3 답변 문장 원문과 후검증 결과. 문장은 검증에 실패해도 남긴다. */
    public void recordAnswer(String answerText, String answerStatus) {
        this.answerText = answerText;
        this.answerStatus = answerStatus;
    }

    public String getAnswerText() {
        return answerText;
    }

    public String getAnswerStatus() {
        return answerStatus;
    }

    public Long getId() {
        return id;
    }

    public EvalRun getRun() {
        return run;
    }

    public String getQuestionId() {
        return questionId;
    }

    public String getQtype() {
        return qtype;
    }

    public String getExpectedAction() {
        return expectedAction;
    }

    public String getActualAction() {
        return actualAction;
    }

    public String getGeneratedSql() {
        return generatedSql;
    }

    public boolean isCorrect() {
        return correct;
    }

    public String getErrorType() {
        return errorType;
    }

    public String getStatus() {
        return status;
    }

    public int getAttempts() {
        return attempts;
    }

    public int getPromptTokens() {
        return promptTokens;
    }

    public int getCompletionTokens() {
        return completionTokens;
    }

    public int getElapsedMs() {
        return elapsedMs;
    }

    public String getDetail() {
        return detail;
    }
}
