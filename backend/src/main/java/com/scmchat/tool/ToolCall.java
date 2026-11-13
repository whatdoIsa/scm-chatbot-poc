package com.scmchat.tool;

import java.time.LocalDate;

/** 검증과 정규화를 거친 도구 호출. 그 도구와 출력 방식이 쓰지 않는 인자는 null이다. */
public record ToolCall(
        ToolName tool,
        ToolOutput output,
        String sku,
        String location,
        Integer days,
        LocalDate startDate,
        LocalDate endDate,
        Integer topN,
        Boolean ascending) {
}
