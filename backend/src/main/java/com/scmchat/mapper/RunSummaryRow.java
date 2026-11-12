package com.scmchat.mapper;

import java.math.BigDecimal;

public record RunSummaryRow(long runId, String promptVersion, String model, BigDecimal temperature, int seed, int repeatNo, int total, int correct, BigDecimal accuracy, BigDecimal sqlExecSuccessRate, BigDecimal formatOkRate, int avgElapsedMs) {
}
