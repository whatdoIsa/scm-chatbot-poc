package com.scmchat.mapper;

import java.math.BigDecimal;

public record TypeSummaryRow(String type, int total, int correct, BigDecimal accuracy) {
}
