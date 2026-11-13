package com.scmchat.tool;

import java.util.Arrays;
import java.util.Optional;
import java.util.Set;

/** 조회 도구 6개. PRD 5.3의 지표 정의 6개와 1:1로 대응한다. */
public enum ToolName {
    AVG_DAILY_SALES("avg_daily_sales", Set.of(ToolOutput.VALUE, ToolOutput.RANKING)),
    DAYS_OF_SUPPLY("days_of_supply", Set.of(ToolOutput.VALUE, ToolOutput.RANKING)),
    STOCKOUT_RATE("stockout_rate", Set.of(ToolOutput.VALUE, ToolOutput.RANKING)),
    FORECAST_ACCURACY("forecast_accuracy", Set.of(ToolOutput.VALUE, ToolOutput.RANKING)),
    BELOW_SAFETY_STOCK("below_safety_stock", Set.of(ToolOutput.LIST, ToolOutput.COUNT)),
    REORDER_NEEDED("reorder_needed", Set.of(ToolOutput.LIST, ToolOutput.COUNT));

    private final String value;
    private final Set<ToolOutput> outputs;

    ToolName(String value, Set<ToolOutput> outputs) {
        this.value = value;
        this.outputs = outputs;
    }

    public String value() {
        return value;
    }

    public Set<ToolOutput> outputs() {
        return outputs;
    }

    public static Optional<ToolName> fromValue(String value) {
        return Arrays.stream(values()).filter(tool -> tool.value.equals(value)).findFirst();
    }
}
