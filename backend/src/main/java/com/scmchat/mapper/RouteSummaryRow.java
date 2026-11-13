package com.scmchat.mapper;

/** v3 실행의 경로별 문항 수와 정답 수. 라우터 호출이 실패한 문항의 route는 none이다. */
public record RouteSummaryRow(String route, int total, int correct) {
}
