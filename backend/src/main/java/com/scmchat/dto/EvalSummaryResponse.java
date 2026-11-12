package com.scmchat.dto;

import java.math.BigDecimal;
import java.util.List;

public record EvalSummaryResponse(List<Run> runs) {

    public record Run(
            long runId,
            String promptVersion,
            String model,
            BigDecimal temperature,
            int seed,
            int repeatNo,
            int total,
            int correct,
            BigDecimal accuracy,
            BigDecimal sqlExecSuccessRate,
            BigDecimal formatOkRate,
            int avgElapsedMs,
            List<TypeAccuracy> byType,
            List<ErrorTypeCount> errorTypes) {
    }

    public record TypeAccuracy(String type, int total, int correct, BigDecimal accuracy) {
    }

    public record ErrorTypeCount(String errorType, int count) {
    }
}
