package com.scmchat.tool;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

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

    /** 응답과 로그에 남길 인자. 라우터 출력과 같은 키를 쓰고 쓰지 않는 인자는 뺀다. */
    public Map<String, Object> toArgsMap() {
        Map<String, Object> args = new LinkedHashMap<>();
        putIfPresent(args, "sku", sku);
        putIfPresent(args, "location", location);
        putIfPresent(args, "days", days);
        putIfPresent(args, "start_date", startDate == null ? null : startDate.toString());
        putIfPresent(args, "end_date", endDate == null ? null : endDate.toString());
        args.put("output", output.value());
        putIfPresent(args, "top_n", topN);
        putIfPresent(args, "order", ascending == null ? null : ascending ? "asc" : "desc");
        return args;
    }

    private static void putIfPresent(Map<String, Object> args, String key, Object value) {
        if (value != null) {
            args.put(key, value);
        }
    }
}
