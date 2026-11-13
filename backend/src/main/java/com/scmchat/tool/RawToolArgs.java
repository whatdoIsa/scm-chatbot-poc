package com.scmchat.tool;

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
}
