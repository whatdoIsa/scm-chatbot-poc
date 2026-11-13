package com.scmchat.tool;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * v5 도구 경로 점검(PRD 12.3). 질문은 네 골든셋과 겹치지 않게 새로 썼다.
 * 도구 호출은 라우터가 낸 것으로 보고 넣는다. 점검은 호출이 질문과 맞는지만 본다.
 */
class ToolRouteGuardTest {

    private final ToolRouteGuard guard = new ToolRouteGuard();

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', textBlock = """
            SKU-012의 재고보유일수는?                         | DAYS_OF_SUPPLY     | VALUE
            서울센터 재고 보유 일수 알려줘                     | DAYS_OF_SUPPLY     | VALUE
            품목 전체 DOS                                      | DAYS_OF_SUPPLY     | VALUE
            충청권 결품률                                      | STOCKOUT_RATE      | VALUE
            지난달 음료 결품 비율은?                           | STOCKOUT_RATE      | VALUE
            최근 7일 일평균 판매량                             | AVG_DAILY_SALES    | VALUE
            SKU-3의 최근 2주 하루 평균 출고량                  | AVG_DAILY_SALES    | VALUE
            부산센터 예측 정확도                               | FORECAST_ACCURACY  | VALUE
            안전재고에 못 미치는 SKU-거점 수                   | BELOW_SAFETY_STOCK | COUNT
            영남권 안전재고 미달 목록                          | BELOW_SAFETY_STOCK | LIST
            현재 안전재고보다 적은 SKU-거점은?                 | BELOW_SAFETY_STOCK | LIST
            재주문이 필요한 SKU-거점 목록                      | REORDER_NEEDED     | LIST
            지금 발주해야 하는 SKU-거점은 몇 곳?               | REORDER_NEEDED     | COUNT
            """)
    void keepsToolCallsThatMatchTheQuestion(String question, ToolName tool, ToolOutput output) {
        ToolCall call = call(tool, output, null);

        ToolRouteGuard.Decision decision = guard.check(question, call);

        assertThat(decision.reroute()).as(decision.rerouteReason()).isFalse();
        assertThat(decision.call()).isEqualTo(call);
        assertThat(decision.orderCorrected()).isFalse();
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', textBlock = """
            이번 주 SKU-010 출고량                             | AVG_DAILY_SALES    | VALUE   | metric
            그저께 서울센터 판매량 합계                        | AVG_DAILY_SALES    | VALUE   | metric
            SKU-015의 이번 주 예측 수량은?                     | FORECAST_ACCURACY  | VALUE   | metric
            재고 괜찮아?                                       | DAYS_OF_SUPPLY     | VALUE   | metric
            그 센터 결품 상황은 어때?                          | STOCKOUT_RATE      | VALUE   | metric
            서울센터 SKU-031의 재주문점은?                     | REORDER_NEEDED     | COUNT   | metric
            광주센터 SKU-006 안전재고 수량은?                  | BELOW_SAFETY_STOCK | COUNT   | metric
            10월에 결품이 있었던 SKU-거점 수                   | STOCKOUT_RATE      | VALUE   | metric
            리드타임이 10일인 SKU-거점 중 안전재고 미달인 것   | BELOW_SAFETY_STOCK | COUNT   | condition
            입고 예정이 없으면서 안전재고 미달인 SKU-거점      | BELOW_SAFETY_STOCK | LIST    | condition
            재주문이 필요하면서 안전재고도 미달인 SKU-거점     | REORDER_NEEDED     | LIST    | condition
            재고보유일수가 5일 미만인 SKU-거점 수              | DAYS_OF_SUPPLY     | VALUE   | metric value
            재고일수가 리드타임보다 짧은 품목                  | DAYS_OF_SUPPLY     | RANKING | metric value
            예측 정확도가 0.8을 넘는 품목 수                   | FORECAST_ACCURACY  | VALUE   | metric value
            카테고리별 결품률                                  | STOCKOUT_RATE      | VALUE   | groups
            센터별 최근 7일 일평균 판매량                      | AVG_DAILY_SALES    | VALUE   | groups
            결품률이 가장 낮은 센터는?                         | STOCKOUT_RATE      | RANKING | ranks
            예측 정확도가 제일 높은 거점은 어디야?             | FORECAST_ACCURACY  | RANKING | ranks
            결품률이 높은 권역 순으로 보여줘                   | STOCKOUT_RATE      | RANKING | ranks
            최근 7일 일평균 판매량이 가장 많은 지역은?         | AVG_DAILY_SALES    | RANKING | ranks
            """)
    void sendsMismatchedToolCallsToTheSqlStage(String question, ToolName tool, ToolOutput output, String reason) {
        ToolRouteGuard.Decision decision = guard.check(question, call(tool, output, true));

        assertThat(decision.reroute()).isTrue();
        assertThat(decision.rerouteReason()).contains(reason);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', textBlock = """
            재고보유일수가 가장 긴 품목 3개                    | DAYS_OF_SUPPLY    | true  | false
            결품률 상위 5개 품목                               | STOCKOUT_RATE     | true  | false
            결품률이 높은 품목 2개는?                          | STOCKOUT_RATE     | true  | false
            예측이 가장 잘 맞은 품목 3개                       | FORECAST_ACCURACY | true  | false
            재고일수가 제일 짧은 품목은?                       | DAYS_OF_SUPPLY    | false | true
            최근 14일 일평균 판매량 하위 4개 품목              | AVG_DAILY_SALES   | false | true
            예측 오차가 가장 큰 품목 2개                       | FORECAST_ACCURACY | false | true
            예측이 가장 안 맞은 품목                           | FORECAST_ACCURACY | false | true
            """)
    void correctsTheRankingOrderWhenTheQuestionSaysOneDirection(String question, ToolName tool, boolean modelAscending,
            boolean expectedAscending) {
        ToolRouteGuard.Decision decision = guard.check(question, call(tool, ToolOutput.RANKING, modelAscending));

        assertThat(decision.reroute()).as(decision.rerouteReason()).isFalse();
        assertThat(decision.orderCorrected()).isTrue();
        assertThat(decision.call().ascending()).isEqualTo(expectedAscending);
        assertThat(decision.call().tool()).isEqualTo(tool);
        assertThat(decision.call().topN()).isEqualTo(3);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', textBlock = """
            결품률 순위 3개                                    | true
            재고보유일수가 가장 긴 품목 3개                    | false
            재고일수가 가장 짧은 품목 3개                      | true
            """)
    void leavesTheOrderAloneWhenItAlreadyMatchesOrTheQuestionIsSilent(String question, boolean modelAscending) {
        ToolCall call = call(question.contains("결품률") ? ToolName.STOCKOUT_RATE : ToolName.DAYS_OF_SUPPLY,
                ToolOutput.RANKING, modelAscending);

        ToolRouteGuard.Decision decision = guard.check(question, call);

        assertThat(decision.reroute()).isFalse();
        assertThat(decision.orderCorrected()).isFalse();
        assertThat(decision.call()).isEqualTo(call);
    }

    private static ToolCall call(ToolName tool, ToolOutput output, Boolean ascending) {
        boolean ranking = output == ToolOutput.RANKING;
        return new ToolCall(tool, output, null, null, tool == ToolName.AVG_DAILY_SALES ? 7 : null, null, null,
                ranking ? 3 : null, ranking ? ascending : null, null, null, null);
    }
}
