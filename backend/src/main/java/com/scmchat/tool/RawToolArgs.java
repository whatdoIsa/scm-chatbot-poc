package com.scmchat.tool;

import java.util.LinkedHashMap;
import java.util.Map;

/** 라우터가 낸 도구 인자 원문. 쓰지 않는 문자열은 빈 값, 쓰지 않는 정수는 0이다(API_SPEC 8.2). */
public record RawToolArgs(
        String sku,
        String location,
        int days,
        String startDate,
        String endDate,
        String output,
        int topN,
        String order) {

    /** 검증에 실패한 호출을 응답과 로그에 남길 때 쓴다. 빈 문자열과 0은 뺀다. */
    public Map<String, Object> toArgsMap() {
        Map<String, Object> args = new LinkedHashMap<>();
        putIfPresent(args, "sku", sku);
        putIfPresent(args, "location", location);
        putIfPresent(args, "days", days == 0 ? null : days);
        putIfPresent(args, "start_date", startDate);
        putIfPresent(args, "end_date", endDate);
        putIfPresent(args, "output", output);
        putIfPresent(args, "top_n", topN == 0 ? null : topN);
        putIfPresent(args, "order", order);
        return args;
    }

    private static void putIfPresent(Map<String, Object> args, String key, Object value) {
        if (value != null && !(value instanceof String text && text.isBlank())) {
            args.put(key, value);
        }
    }
}
