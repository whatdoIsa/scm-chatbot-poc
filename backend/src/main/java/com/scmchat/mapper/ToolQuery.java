package com.scmchat.mapper;

import java.time.LocalDate;

/**
 * 조회 도구 쿼리의 바인딩 값. 쓰지 않는 값은 null이다.
 * fromDate ~ toDate는 판매 또는 결품을 세는 기간이고, days는 일평균의 나눗수다.
 */
public record ToolQuery(
        String sku,
        String location,
        LocalDate asOfDate,
        LocalDate fromDate,
        LocalDate toDate,
        Integer days,
        Integer limit,
        boolean ascending) {
}
