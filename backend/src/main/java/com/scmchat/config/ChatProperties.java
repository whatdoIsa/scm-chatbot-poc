package com.scmchat.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties(prefix = "chat")
@Validated
public record ChatProperties(
        @NotBlank String promptVersion,
        @NotNull LocalDate asOfDate,
        @NotNull LocalDate dataStartDate,
        @Min(1) int maxRows,
        @Min(1) int validatorLimit,
        @NotBlank String promptsDir,
        @NotNull @Valid Llm llm) {

    public record Llm(
            @NotBlank String provider,
            @NotBlank String baseUrl,
            @NotBlank String model,
            double temperature,
            int seed,
            @Min(1) int numCtx,
            @Min(1) int timeoutSeconds) {
    }
}
