package com.scmchat.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties(prefix = "eval")
@Validated
public record EvalProperties(
        @NotBlank String goldenPath,
        @NotBlank String goldenHashPath,
        @NotBlank String reportDir,
        @NotEmpty List<String> versions,
        @Min(1) int repeat) {
}
