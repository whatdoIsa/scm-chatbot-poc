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
