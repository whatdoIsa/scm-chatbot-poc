package com.scmchat.dto;

import java.time.LocalDate;

public record MetaResponse(LocalDate asOfDate, String promptVersion, String model, String llmProvider) {
}
