package com.scmchat.tool;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * v5 도구 경로 점검(PRD 12.3). 질문은 네 골든셋, 라우터 예시, 라우터 개발 세트 1·2와 겹치지 않게 썼다.
 * 도구 호출은 라우터가 낸 것으로 보고 넣는다. 점검은 호출이 질문과 맞는지만 본다.
 * "리뷰" 표시가 붙은 행은 v5 코드리뷰에서 잘못 판단하던 질문을 그대로 옮긴 재현 사례다.
 */
class ToolRouteGuardTest {

    private static final Dimensions DIMENSIONS = new Dimensions() {
        @Override
        public boolean skuExists(String sku) {
            return true;
        }

        @Override
        public List<String> locationNames() {
            return List.of("서울센터", "부산센터", "대전센터", "광주센터", "대구센터");
        }

        @Override
        public List<String> regions() {
            return List.of("수도권", "영남권", "충청권", "호남권");
        }

        @Override
        public List<String> categories() {
            return List.of("스낵", "음료", "개인위생", "냉동식품", "생활용품");
        }
    };

    private final ToolRouteGuard guard = new ToolRouteGuard(DIMENSIONS);

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
            서울센터에서 결품률이 가장 높은 품목               | STOCKOUT_RATE      | RANKING
            음료 카테고리에서 결품률이 가장 높은 품목은?       | STOCKOUT_RATE      | RANKING
            서울센터는 어느 품목이 재고일수가 가장 길어?       | DAYS_OF_SUPPLY     | RANKING
            결품률 제일 높은 품목이 뭐야 대구센터는?           | STOCKOUT_RATE      | RANKING
            결품률이 가장 높은 품목 부산센터 알려줘            | STOCKOUT_RATE      | RANKING
            결품률 상위 5개 품목                               | STOCKOUT_RATE      | RANKING
            B등급 품목 중 예측 정확도가 높은 품목 2개          | FORECAST_ACCURACY  | RANKING
            """)
    void keepsToolCallsThatMatchTheQuestion(String question, ToolName tool, ToolOutput output) {
        ToolCall call = call(tool, output, output == ToolOutput.RANKING ? Boolean.FALSE : null);

        ToolRouteGuard.Decision decision = guard.check(question, call);

        assertThat(decision.reason()).as(question).isNull();
        assertThat(decision.call().tool()).isEqualTo(tool);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', textBlock = """
            이번 주 SKU-010 출고량                             | AVG_DAILY_SALES    | VALUE   | METRIC_NOT_NAMED
            그제 광주센터 판매량 합계                          | AVG_DAILY_SALES    | VALUE   | METRIC_NOT_NAMED
            서울센터의 평균 리드타임은?                        | AVG_DAILY_SALES    | VALUE   | METRIC_NOT_NAMED
            평균 재고가 가장 많은 품목                         | AVG_DAILY_SALES    | RANKING | METRIC_NOT_NAMED
            SKU-015의 이번 주 예측 수량은?                     | FORECAST_ACCURACY  | VALUE   | METRIC_NOT_NAMED
            WMAPE가 가장 높은 품목 3개                         | FORECAST_ACCURACY  | RANKING | METRIC_NOT_NAMED
            요즘 재고 상태 어때?                               | DAYS_OF_SUPPLY     | VALUE   | METRIC_NOT_NAMED
            저번 그 거점 결품은 좀 나아졌어?                   | STOCKOUT_RATE      | VALUE   | METRIC_NOT_NAMED
            서울센터 SKU-031의 재주문점은?                     | REORDER_NEEDED     | COUNT   | METRIC_NOT_NAMED
            대구센터 SKU-044의 안전재고는 몇 개로 잡혀 있어?   | BELOW_SAFETY_STOCK | COUNT   | METRIC_NOT_NAMED
            10월에 결품이 있었던 SKU-거점 수                   | STOCKOUT_RATE      | VALUE   | METRIC_NOT_NAMED
            리드타임이 10일인 SKU-거점 중 안전재고 미달인 것   | BELOW_SAFETY_STOCK | COUNT   | UNSUPPORTED_CONDITION
            입고 예정이 없으면서 안전재고 미달인 SKU-거점      | BELOW_SAFETY_STOCK | LIST    | UNSUPPORTED_CONDITION
            재주문이 필요하면서 안전재고도 미달인 SKU-거점     | REORDER_NEEDED     | LIST    | UNSUPPORTED_CONDITION
            재고보유일수가 5일 미만인 SKU-거점 수              | DAYS_OF_SUPPLY     | VALUE   | VALUE_CONDITION
            재고보유일수 3일 미만 SKU-거점 수                  | DAYS_OF_SUPPLY     | VALUE   | VALUE_CONDITION
            재고보유일수가 3일 이하 품목은?                    | DAYS_OF_SUPPLY     | RANKING | VALUE_CONDITION
            재고일수가 리드타임보다 짧은 품목                  | DAYS_OF_SUPPLY     | RANKING | VALUE_CONDITION
            예측 정확도가 0.8을 넘는 품목 수                   | FORECAST_ACCURACY  | VALUE   | VALUE_CONDITION
            카테고리별 결품률                                  | STOCKOUT_RATE      | VALUE   | GROUPED
            센터별 최근 7일 일평균 판매량                      | AVG_DAILY_SALES    | VALUE   | GROUPED
            결품률이 가장 낮은 센터는?                         | STOCKOUT_RATE      | RANKING | NON_ITEM_RANKING
            결품률이 가장 높은 센터는?                         | STOCKOUT_RATE      | VALUE   | NON_ITEM_RANKING
            예측 정확도가 제일 좋은 창고는 어디?               | FORECAST_ACCURACY  | RANKING | NON_ITEM_RANKING
            결품률이 낮은 지역 순서로                          | STOCKOUT_RATE      | RANKING | NON_ITEM_RANKING
            최근 7일 일평균 판매량이 가장 많은 지역은?         | AVG_DAILY_SALES    | RANKING | NON_ITEM_RANKING
            결품률이 가장 높은 센터 3곳                        | STOCKOUT_RATE      | RANKING | NON_ITEM_RANKING
            결품률 상위 3개 센터                               | STOCKOUT_RATE      | RANKING | NON_ITEM_RANKING
            재고일수가 가장 짧은 거점 TOP3                     | DAYS_OF_SUPPLY     | RANKING | NON_ITEM_RANKING
            결품률이 가장 높은 카테고리 3개                    | STOCKOUT_RATE      | RANKING | NON_ITEM_RANKING
            카테고리 중 결품률이 가장 높은 곳                  | STOCKOUT_RATE      | RANKING | NON_ITEM_RANKING
            권역 중에서 예측 정확도가 제일 낮은 곳은?          | FORECAST_ACCURACY  | RANKING | NON_ITEM_RANKING
            """)
    void sendsMismatchedToolCallsToTheSqlStage(String question, ToolName tool, ToolOutput output,
            ToolRouteGuard.Reason reason) {
        ToolRouteGuard.Decision decision = guard.check(question, call(tool, output, true));

        assertThat(decision.reroute()).isTrue();
        assertThat(decision.reason()).as(question).isEqualTo(reason);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', textBlock = """
            재고보유일수가 가장 긴 품목 3개                    | DAYS_OF_SUPPLY    | true  | false
            결품률이 높은 품목 2개는?                          | STOCKOUT_RATE     | true  | false
            예측이 가장 잘 맞은 품목 3개                       | FORECAST_ACCURACY | true  | false
            재고일수가 짧은 순으로 품목 3개                    | DAYS_OF_SUPPLY    | false | true
            최근 14일 일평균 판매량 하위 4개 품목              | AVG_DAILY_SALES   | false | true
            예측 오차가 가장 큰 품목 2개                       | FORECAST_ACCURACY | false | true
            예측이 가장 안 맞은 품목                           | FORECAST_ACCURACY | false | true
            최근 7일 일평균 판매량 기준 가장 잘 안 팔리는 품목 3개 | AVG_DAILY_SALES | false | true
            예측 정확도가 가장 잘 안 나오는 품목               | FORECAST_ACCURACY | false | true
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

    /** 모델의 방향이 이미 맞거나 질문이 방향을 말하지 않으면 손대지 않는다. 마지막 두 행은 리뷰의 재현 사례다. */
    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', textBlock = """
            결품률 순위 3개                                    | STOCKOUT_RATE     | true
            재고보유일수가 가장 긴 품목 3개                    | DAYS_OF_SUPPLY    | false
            재고일수가 가장 짧은 품목 3개                      | DAYS_OF_SUPPLY    | true
            일평균 판매량 기준 제일 잘 안 나가는 품목          | AVG_DAILY_SALES   | true
            오차율 기준 예측 정확도가 가장 높은 품목           | FORECAST_ACCURACY | false
            """)
    void leavesTheOrderAloneWhenItAlreadyMatchesOrTheQuestionIsSilent(String question, ToolName tool,
            boolean modelAscending) {
        ToolCall call = call(tool, ToolOutput.RANKING, modelAscending);

        ToolRouteGuard.Decision decision = guard.check(question, call);

        assertThat(decision.reroute()).as(decision.rerouteReason()).isFalse();
        assertThat(decision.orderCorrected()).isFalse();
        assertThat(decision.call()).isEqualTo(call);
    }

    private static ToolCall call(ToolName tool, ToolOutput output, Boolean ascending) {
        boolean ranking = output == ToolOutput.RANKING;
        return new ToolCall(tool, output, null, null, tool == ToolName.AVG_DAILY_SALES ? 7 : null, null, null,
                ranking ? 3 : null, ranking ? ascending : null, null, null, null);
    }
}
