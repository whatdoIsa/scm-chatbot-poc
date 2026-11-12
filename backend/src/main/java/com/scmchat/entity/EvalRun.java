package com.scmchat.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "eval_run")
public class EvalRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "prompt_version", nullable = false, length = 8)
    private String promptVersion;

    @Column(nullable = false, length = 64)
    private String model;

    @Column(nullable = false, precision = 4, scale = 2)
    private BigDecimal temperature;

    @Column(nullable = false)
    private int seed;

    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "repeat_no", nullable = false)
    private int repeatNo;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "golden_sha256", nullable = false, length = 64)
    private String goldenSha256;

    @Column(name = "started_at", nullable = false)
    private OffsetDateTime startedAt;

    @Column(name = "finished_at")
    private OffsetDateTime finishedAt;

    private Integer total;

    private Integer correct;

    @Column(precision = 5, scale = 4)
    private BigDecimal accuracy;

    @Column(name = "sql_exec_success_rate", precision = 5, scale = 4)
    private BigDecimal sqlExecSuccessRate;

    @Column(name = "format_ok_rate", precision = 5, scale = 4)
    private BigDecimal formatOkRate;

    @Column(name = "avg_elapsed_ms")
    private Integer avgElapsedMs;

    @Column(columnDefinition = "text")
    private String note;

    protected EvalRun() {
    }

    public EvalRun(String promptVersion, String model, BigDecimal temperature, int seed, int repeatNo,
            String goldenSha256, OffsetDateTime startedAt) {
        this.promptVersion = promptVersion;
        this.model = model;
        this.temperature = temperature;
        this.seed = seed;
        this.repeatNo = repeatNo;
        this.goldenSha256 = goldenSha256;
        this.startedAt = startedAt;
    }

    /** 모든 문항을 채점한 뒤 요약 값을 채운다. */
    public void complete(OffsetDateTime finishedAt, int total, int correct, BigDecimal accuracy,
            BigDecimal sqlExecSuccessRate, BigDecimal formatOkRate, int avgElapsedMs) {
        this.finishedAt = finishedAt;
        this.total = total;
        this.correct = correct;
        this.accuracy = accuracy;
        this.sqlExecSuccessRate = sqlExecSuccessRate;
        this.formatOkRate = formatOkRate;
        this.avgElapsedMs = avgElapsedMs;
    }

    public Long getId() {
        return id;
    }

    public String getPromptVersion() {
        return promptVersion;
    }

    public String getModel() {
        return model;
    }

    public BigDecimal getTemperature() {
        return temperature;
    }

    public int getSeed() {
        return seed;
    }

    public int getRepeatNo() {
        return repeatNo;
    }

    public String getGoldenSha256() {
        return goldenSha256;
    }

    public OffsetDateTime getStartedAt() {
        return startedAt;
    }

    public OffsetDateTime getFinishedAt() {
        return finishedAt;
    }

    public Integer getTotal() {
        return total;
    }

    public Integer getCorrect() {
        return correct;
    }

    public BigDecimal getAccuracy() {
        return accuracy;
    }

    public BigDecimal getSqlExecSuccessRate() {
        return sqlExecSuccessRate;
    }

    public BigDecimal getFormatOkRate() {
        return formatOkRate;
    }

    public Integer getAvgElapsedMs() {
        return avgElapsedMs;
    }

    public String getNote() {
        return note;
    }
}
