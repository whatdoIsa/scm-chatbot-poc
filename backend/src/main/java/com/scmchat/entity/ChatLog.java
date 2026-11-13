package com.scmchat.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "chat_log")
public class ChatLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, columnDefinition = "text")
    private String question;

    @Column(name = "prompt_version", nullable = false, length = 8)
    private String promptVersion;

    @Column(nullable = false, length = 64)
    private String model;

    @Column(length = 16)
    private String action;

    @Column(nullable = false, length = 32)
    private String status;

    @Column(name = "generated_sql", columnDefinition = "text")
    private String generatedSql;

    @Column(name = "row_count")
    private Integer rowCount;

    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(nullable = false)
    private int attempts;

    @Column(name = "prompt_tokens", nullable = false)
    private int promptTokens;

    @Column(name = "completion_tokens", nullable = false)
    private int completionTokens;

    @Column(name = "elapsed_ms", nullable = false)
    private int elapsedMs;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

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

    protected ChatLog() {
    }

    public ChatLog(String question, String promptVersion, String model, String action, String status,
            String generatedSql, Integer rowCount, int attempts, int promptTokens, int completionTokens,
            int elapsedMs, String errorMessage) {
        this.question = question;
        this.promptVersion = promptVersion;
        this.model = model;
        this.action = action;
        this.status = status;
        this.generatedSql = generatedSql;
        this.rowCount = rowCount;
        this.attempts = attempts;
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        this.elapsedMs = elapsedMs;
        this.errorMessage = errorMessage;
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

    @PrePersist
    void assignCreatedAt() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }

    public Long getId() {
        return id;
    }

    public String getQuestion() {
        return question;
    }

    public String getPromptVersion() {
        return promptVersion;
    }

    public String getModel() {
        return model;
    }

    public String getAction() {
        return action;
    }

    public String getStatus() {
        return status;
    }

    public String getGeneratedSql() {
        return generatedSql;
    }

    public Integer getRowCount() {
        return rowCount;
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

    public String getErrorMessage() {
        return errorMessage;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
