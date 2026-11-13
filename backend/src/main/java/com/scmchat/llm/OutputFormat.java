package com.scmchat.llm;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 모델에 강제하는 출력 JSON 스키마(API_SPEC 3.1, 8.2). Ollama 요청의 format에 그대로 들어간다.
 * 키 순서가 실행마다 바뀌면 모델에 전달되는 스키마가 달라지므로 순서가 고정된 맵을 쓴다.
 * ACTION은 v0~v2 측정 때의 스키마이며 바꾸지 않는다.
 */
public enum OutputFormat {
    ACTION(actionSchema()),
    ROUTE(routeSchema()),
    ANSWER(answerSchema());

    private final Map<String, Object> schema;

    OutputFormat(Map<String, Object> schema) {
        this.schema = schema;
    }

    public Map<String, Object> schema() {
        return schema;
    }

    private static Map<String, Object> actionSchema() {
        Map<String, Object> action = new LinkedHashMap<>();
        action.put("type", "string");
        action.put("enum", List.of("sql", "clarify", "refuse"));

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("action", action);
        properties.put("sql", Map.of("type", "string"));
        properties.put("message", Map.of("type", "string"));
        return object(properties);
    }

    private static Map<String, Object> routeSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("route", enumOf("tool", "sql", "clarify", "refuse"));
        properties.put("tool", enumOf("", "avg_daily_sales", "days_of_supply", "stockout_rate", "forecast_accuracy",
                "below_safety_stock", "reorder_needed"));
        properties.put("sku", Map.of("type", "string"));
        properties.put("location", Map.of("type", "string"));
        properties.put("days", Map.of("type", "integer"));
        properties.put("start_date", Map.of("type", "string"));
        properties.put("end_date", Map.of("type", "string"));
        properties.put("output", enumOf("", "value", "ranking", "list", "count"));
        properties.put("top_n", Map.of("type", "integer"));
        properties.put("order", enumOf("", "asc", "desc"));
        properties.put("message", Map.of("type", "string"));
        return object(properties);
    }

    private static Map<String, Object> answerSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("answer", Map.of("type", "string"));
        return object(properties);
    }

    private static Map<String, Object> enumOf(String... values) {
        Map<String, Object> property = new LinkedHashMap<>();
        property.put("type", "string");
        property.put("enum", List.of(values));
        return property;
    }

    /** 모든 속성을 required로 둔다. */
    private static Map<String, Object> object(Map<String, Object> properties) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.copyOf(properties.keySet()));
        return schema;
    }
}
