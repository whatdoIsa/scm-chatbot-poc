package com.scmchat.answer;

import static org.assertj.core.api.Assertions.assertThat;

import com.scmchat.executor.QueryResult;
import com.scmchat.tool.ToolCall;
import com.scmchat.tool.ToolName;
import com.scmchat.tool.ToolOutput;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class NumberVerifierTest {

    private static final LocalDate AS_OF = LocalDate.of(2026, 11, 8);

    private final NumberVerifier verifier = new NumberVerifier();

    @Test
    void acceptsAnIntegerCopiedFromTheResult() {
        assertVerified("SKU-007의 현재 재고는 284개입니다.", "SKU-007의 현재 재고는?", result(List.of("on_hand"), row(284L)));
    }

    @Test
    void acceptsThousandsSeparators() {
        assertVerified("서울센터의 재고금액은 145,739,300원입니다.", "서울센터 재고금액은?",
                result(List.of("inventory_value"), row(new BigDecimal("145739300.00"))));
    }

    @Test
    void acceptsRoundedAndTruncatedDecimals() {
        QueryResult result = result(List.of("days_of_supply"), row(new BigDecimal("14.7186858316221766")));

        assertVerified("재고보유일수는 14.72일입니다.", "재고보유일수는?", result);
        assertVerified("재고보유일수는 약 14.7일입니다.", "재고보유일수는?", result);
        assertVerified("재고보유일수는 약 15일입니다.", "재고보유일수는?", result);
        assertVerified("재고보유일수는 14일 남짓입니다.", "재고보유일수는?", result);
    }

    @Test
    void acceptsARatioWrittenAsAPercentage() {
        QueryResult result = result(List.of("stockout_rate"), row(new BigDecimal("0.10322580645161290323")));

        assertVerified("결품률은 10.3%입니다.", "결품률은?", result);
        assertVerified("결품률은 10 %입니다.", "결품률은?", result);
        assertVerified("결품률은 10.32퍼센트입니다.", "결품률은?", result);
        assertVerified("결품률은 0.1032입니다.", "결품률은?", result);
    }

    @Test
    void acceptsNumbersFromTheQuestionAndToolArguments() {
        ToolCall call = new ToolCall(ToolName.AVG_DAILY_SALES, ToolOutput.RANKING, null, "부산센터", 14,
                null, null, 3, false);
        QueryResult result = result(List.of("sku", "avg_daily_qty"),
                row("SKU-040", new BigDecimal("0.7142857")), row("SKU-046", new BigDecimal("2.4285714")),
                row("SKU-033", new BigDecimal("3.5")));

        Verification verification = verifier.verify(
                "최근 14일 기준 하위 3개 품목은 SKU-040(0.71), SKU-046(2.43), SKU-033(3.5)입니다.",
                new VerificationContext("부산센터에서 최근 2주 일평균 판매량 하위 품목은?", result, call, AS_OF));

        assertThat(verification).isEqualTo(new Verification(true, List.of()));
    }

    @Test
    void acceptsDatePartsOfTheAsOfDateToolPeriodAndDateCells() {
        ToolCall call = new ToolCall(ToolName.STOCKOUT_RATE, ToolOutput.VALUE, "SKU-023", null, null,
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), null, null);

        assertThat(verifier.verify("2026-10-01부터 10월 31일까지 결품률은 10.3%입니다(기준일 2026년 11월 8일).",
                new VerificationContext("전월 결품률은?",
                        result(List.of("stockout_rate"), row(new BigDecimal("0.1032"))), call, AS_OF)).ok()).isTrue();
        assertVerified("차이가 가장 컸던 주는 2026-09-21 주입니다.", "차이가 가장 컸던 주는?",
                result(List.of("week_start"), row(LocalDate.of(2026, 9, 21))));
    }

    @Test
    void acceptsRowCountAndRanks() {
        QueryResult result = result(List.of("sku"), row("SKU-023"), row("SKU-048"), row("SKU-036"));

        assertVerified("총 3개 품목이며 1위는 SKU-023, 2위는 SKU-048, 3위는 SKU-036입니다.", "결품률 워스트 품목은?", result);
    }

    @Test
    void ignoresDigitsInsideIdentifiersAndAllowsDigitsQuotedFromTextCells() {
        assertVerified("SKU-031은 냉동만두 1kg이며 단가는 19,350원입니다.", "SKU-031의 품목명과 단가는?",
                result(List.of("name", "unit_cost"), row("냉동만두 1kg", new BigDecimal("19350.00"))));
        assertVerified("LOC-02에는 해당 품목이 없습니다.", "부산센터 결품 품목은?", result(List.of("cnt"), row(0L)));
    }

    @Test
    void acceptsASentenceWithoutNumbers() {
        assertVerified("광주센터는 호남권에 있습니다.", "광주센터는 어느 권역에 있어?", result(List.of("region"), row("호남권")));
    }

    @Test
    void rejectsANumberThatIsNowhereInTheResult() {
        assertRejected("SKU-007의 현재 재고는 300개입니다.", "SKU-007의 현재 재고는?",
                result(List.of("on_hand"), row(284L)), "300");
    }

    @Test
    void rejectsASumOfTwoCells() {
        assertRejected("두 거점의 판매량은 합쳐서 220개입니다.", "거점별 판매량은?",
                result(List.of("qty"), row(120L), row(100L)), "220");
    }

    @Test
    void rejectsAnOffByOneValue() {
        assertRejected("재주문이 필요한 품목은 6개입니다.", "재주문이 필요한 품목 수는?", result(List.of("cnt"), row(5L)), "6");
    }

    @Test
    void rejectsRoundingUpWhereHalfUpAndTruncationDisagree() {
        QueryResult result = result(List.of("days_of_supply"), row(new BigDecimal("14.7186858316221766")));

        assertRejected("재고보유일수는 14.8일입니다.", "재고보유일수는?", result, "14.8");
        assertRejected("재고보유일수는 16일입니다.", "재고보유일수는?", result, "16");
    }

    @Test
    void rejectsAUnitConversion() {
        assertRejected("재고금액은 약 1.46억 원입니다.", "서울센터 재고금액은?",
                result(List.of("inventory_value"), row(new BigDecimal("145739300.00"))), "1.46");
    }

    @Test
    void rejectsAPercentageThatIsNotTheRatioTimesHundred() {
        QueryResult result = result(List.of("stockout_rate"), row(new BigDecimal("0.1032")));

        assertRejected("결품률은 1.03%입니다.", "결품률은?", result, "1.03%");
        assertRejected("결품률은 89.7%가 아닌 날의 나머지입니다.", "결품률은?", result, "89.7%");
    }

    @Test
    void doesNotTreatAPlainNumberAsAPercentage() {
        assertRejected("결품률은 10.3입니다.", "결품률은?", result(List.of("stockout_rate"), row(new BigDecimal("0.1032"))),
                "10.3");
    }

    @Test
    void rejectsAWindowTheModelWasNotGiven() {
        assertRejected("최근 28일 기준 재고보유일수는 14.7일입니다.", "재고보유일수는?",
                result(List.of("days_of_supply"), row(new BigDecimal("14.7187"))), "28");
    }

    @Test
    void reportsEveryUnverifiedNumberInOrder() {
        Verification verification = verifier.verify("재고는 284개, 일평균 판매는 31개, 보유일수는 9.2일입니다.",
                new VerificationContext("재고는?", result(List.of("on_hand"), row(284L)), null, AS_OF));

        assertThat(verification).isEqualTo(new Verification(false, List.of("31", "9.2")));
    }

    @Test
    void ignoresNullAndNonFiniteCells() {
        QueryResult result = result(List.of("accuracy", "ratio"), row(null, Double.NaN));

        assertRejected("예측 정확도는 0.5입니다.", "예측 정확도는?", result, "0.5");
        assertVerified("예측 정확도를 계산할 수 없습니다.", "예측 정확도는?", result);
    }

    private void assertVerified(String answer, String question, QueryResult result) {
        Verification verification = verifier.verify(answer, new VerificationContext(question, result, null, AS_OF));

        assertThat(verification.unverified()).as(answer).isEmpty();
        assertThat(verification.ok()).isTrue();
    }

    private void assertRejected(String answer, String question, QueryResult result, String... unverified) {
        Verification verification = verifier.verify(answer, new VerificationContext(question, result, null, AS_OF));

        assertThat(verification.ok()).as(answer).isFalse();
        assertThat(verification.unverified()).containsExactly(unverified);
    }

    private static QueryResult result(List<String> columns, List<Object>... rows) {
        return new QueryResult(columns, List.of(rows), false);
    }

    private static List<Object> row(Object... values) {
        return Arrays.asList(values);
    }
}
