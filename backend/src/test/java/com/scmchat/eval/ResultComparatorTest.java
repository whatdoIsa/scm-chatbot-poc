package com.scmchat.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.scmchat.executor.QueryResult;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class ResultComparatorTest {

    private final ResultComparator comparator = new ResultComparator();

    @Test
    void ignoresColumnNames() {
        assertMatch(result(cols("on_hand"), row(284)), result(cols("total_stock"), row(284)), false);
    }

    @Test
    void allowsExtraLabelColumnsInActual() {
        QueryResult expected = result(cols("qty"), row(100), row(200));
        QueryResult actual = result(cols("category", "qty"), row("음료", 100), row("스낵", 200));

        assertMatch(expected, actual, false);
    }

    @Test
    void ignoresColumnOrder() {
        QueryResult expected = result(cols("category", "abc_class"), row("음료", "C"));
        QueryResult actual = result(cols("abc", "cat"), row("C", "음료"));

        assertMatch(expected, actual, false);
    }

    @Test
    void rejectsActualWithFewerColumns() {
        QueryResult expected = result(cols("category", "abc_class"), row("음료", "C"));

        assertMismatch(expected, result(cols("category"), row("음료")), false, "column count");
    }

    @Test
    void doesNotReuseOneActualColumnForTwoExpectedColumns() {
        QueryResult expected = result(cols("a", "b"), row(5, 5));

        assertMismatch(expected, result(cols("x", "y"), row(5, 6)), false, null);
    }

    @Test
    void ignoresRowOrderWhenNotOrderSensitive() {
        QueryResult expected = result(cols("qty"), row(1), row(2), row(3));

        assertMatch(expected, result(cols("qty"), row(3), row(1), row(2)), false);
    }

    @Test
    void comparesRowOrderWhenOrderSensitive() {
        QueryResult expected = result(cols("sku"), row("SKU-033"), row("SKU-008"));

        assertMatch(expected, result(cols("sku"), row("SKU-033"), row("SKU-008")), true);
        assertMismatch(expected, result(cols("sku"), row("SKU-008"), row("SKU-033")), true, null);
    }

    @Test
    void keepsRowsTogetherWhenIgnoringOrder() {
        QueryResult expected = result(cols("sku", "qty"), row("A", 1), row("B", 2));
        QueryResult swapped = result(cols("sku", "qty"), row("A", 2), row("B", 1));

        assertMismatch(expected, swapped, false, "rows do not match as a whole");
    }

    @Test
    void rejectsDifferentRowCount() {
        QueryResult expected = result(cols("on_hand"), row(284));
        QueryResult perLocation = result(cols("on_hand"), row(32), row(58), row(20), row(100), row(74));

        assertMismatch(expected, perLocation, false, "row count differs: expected 1 but was 5");
    }

    @Test
    void acceptsRelativeErrorUpToPointOnePercent() {
        QueryResult expected = result(cols("v"), row(1000));

        assertMatch(expected, result(cols("v"), row(1001)), false);
        assertMatch(expected, result(cols("v"), row(999)), false);
        assertMismatch(expected, result(cols("v"), row(new BigDecimal("1001.01"))), false, null);
    }

    @Test
    void distinguishesGoldenValuesThatDifferByOne() {
        assertMismatch(result(cols("qty"), row(565)), result(cols("qty"), row(564)), false, null);
    }

    @Test
    void usesAbsoluteToleranceWhenExpectedIsZero() {
        QueryResult expected = result(cols("v"), row(0));

        assertMatch(expected, result(cols("v"), row(new BigDecimal("0.0000000005"))), false);
        assertMismatch(expected, result(cols("v"), row(new BigDecimal("0.00001"))), false, null);
    }

    @Test
    void comparesNumbersAcrossJavaTypes() {
        QueryResult expected = result(cols("v"), row(new BigDecimal("9.2427184466019417")));

        assertMatch(expected, result(cols("v"), row(9.2427184466)), false);
        assertMatch(result(cols("v"), row(17L)), result(cols("v"), row(17)), false);
        assertMatch(result(cols("v"), row(17)), result(cols("v"), row(new BigDecimal("17.00"))), false);
    }

    @Test
    void doesNotTreatTextAsNumber() {
        assertMismatch(result(cols("v"), row(17)), result(cols("v"), row("17")), false, null);
    }

    @Test
    void matchesNullOnlyWithNull() {
        QueryResult expected = result(cols("v"), row((Object) null));

        assertMatch(expected, result(cols("v"), row((Object) null)), false);
        assertMismatch(expected, result(cols("v"), row(0)), false, null);
        assertMismatch(result(cols("v"), row(0)), expected, false, null);
    }

    @Test
    void comparesDatesByIsoText() {
        QueryResult expected = result(cols("d"), row(LocalDate.of(2026, 11, 8)));

        assertMatch(expected, result(cols("d"), row(LocalDate.of(2026, 11, 8))), false);
        assertMatch(expected, result(cols("d"), row("2026-11-08")), false);
        assertMismatch(expected, result(cols("d"), row(LocalDate.of(2026, 11, 7))), false, null);
    }

    @Test
    void matchesTwoEmptyResults() {
        assertMatch(result(cols("v")), result(cols("v")), false);
    }

    @Test
    void handlesDuplicateRows() {
        QueryResult expected = result(cols("v"), row(1), row(1), row(2));

        assertMatch(expected, result(cols("v"), row(2), row(1), row(1)), false);
        assertMismatch(expected, result(cols("v"), row(2), row(2), row(1)), false, null);
    }

    private void assertMatch(QueryResult expected, QueryResult actual, boolean orderSensitive) {
        ResultComparator.Comparison comparison = comparator.compare(expected, actual, orderSensitive);
        assertThat(comparison.match()).as(String.valueOf(comparison.reason())).isTrue();
    }

    private void assertMismatch(QueryResult expected, QueryResult actual, boolean orderSensitive, String reason) {
        ResultComparator.Comparison comparison = comparator.compare(expected, actual, orderSensitive);
        assertThat(comparison.match()).isFalse();
        assertThat(comparison.reason()).isNotBlank();
        if (reason != null) {
            assertThat(comparison.reason()).contains(reason);
        }
    }

    private static List<String> cols(String... names) {
        return List.of(names);
    }

    private static List<Object> row(Object... values) {
        return Arrays.asList(values);
    }

    @SafeVarargs
    private static QueryResult result(List<String> columns, List<Object>... rows) {
        return new QueryResult(columns, List.of(rows), false);
    }
}
