package com.scmchat.tool;

import static org.assertj.core.api.Assertions.assertThat;

import com.scmchat.config.ChatProperties;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ToolArgsValidatorTest {

    private static final Dimensions DIMENSIONS = new Dimensions() {
        private final Set<String> skus = Set.of("SKU-007", "SKU-012", "SKU-026");

        @Override
        public boolean skuExists(String sku) {
            return skus.contains(sku);
        }

        @Override
        public List<String> locationNames() {
            return List.of("서울센터", "부산센터", "대전센터");
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

    private final ToolArgsValidator validator = new ToolArgsValidator(DIMENSIONS, new ChatProperties(
            "v1", LocalDate.of(2026, 11, 8), LocalDate.of(2026, 8, 10), 1000, 200, 20, "../prompts",
            new ChatProperties.Llm("fake", "http://localhost:11434", "qwen2.5-coder:7b", 0, 42, 8192, 120)));

    @Test
    void acceptsAvgDailySalesValue() {
        ToolValidation result = validator.validate("avg_daily_sales", args("SKU-012", "", 14, "", "", "value", 0, ""));

        assertThat(result.ok()).isTrue();
        assertThat(result.call()).isEqualTo(new ToolCall(ToolName.AVG_DAILY_SALES, ToolOutput.VALUE,
                "SKU-012", null, 14, null, null, null, null));
    }

    @Test
    void acceptsDaysOfSupplyRanking() {
        ToolValidation result = validator.validate("days_of_supply", args("", "서울센터", 0, "", "", "ranking", 5, "asc"));

        assertThat(result.ok()).isTrue();
        assertThat(result.call()).isEqualTo(new ToolCall(ToolName.DAYS_OF_SUPPLY, ToolOutput.RANKING,
                null, "서울센터", null, null, null, 5, true));
    }

    @Test
    void acceptsStockoutRateWithPeriod() {
        ToolValidation result = validator.validate("stockout_rate",
                args("SKU-026", "", 0, "2026-10-01", "2026-10-31", "value", 0, ""));

        assertThat(result.ok()).isTrue();
        assertThat(result.call().startDate()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(result.call().endDate()).isEqualTo(LocalDate.of(2026, 10, 31));
    }

    @Test
    void acceptsStockoutRateWithoutPeriod() {
        ToolValidation result = validator.validate("stockout_rate", args("", "", 0, "", "", "value", 0, ""));

        assertThat(result.ok()).isTrue();
        assertThat(result.call().startDate()).isNull();
        assertThat(result.call().endDate()).isNull();
    }

    @Test
    void acceptsForecastAccuracyRankingDescending() {
        ToolValidation result = validator.validate("forecast_accuracy", args("", "", 0, "", "", "ranking", 50, "desc"));

        assertThat(result.ok()).isTrue();
        assertThat(result.call().topN()).isEqualTo(50);
        assertThat(result.call().ascending()).isFalse();
    }

    @Test
    void acceptsBelowSafetyStockCount() {
        ToolValidation result = validator.validate("below_safety_stock", args("", "", 0, "", "", "count", 0, ""));

        assertThat(result.ok()).isTrue();
        assertThat(result.call()).isEqualTo(new ToolCall(ToolName.BELOW_SAFETY_STOCK, ToolOutput.COUNT,
                null, null, null, null, null, null, null));
    }

    @Test
    void acceptsReorderNeededList() {
        ToolValidation result = validator.validate("reorder_needed", args("", "대전센터", 0, "", "", "list", 0, ""));

        assertThat(result.ok()).isTrue();
        assertThat(result.call().output()).isEqualTo(ToolOutput.LIST);
        assertThat(result.call().location()).isEqualTo("대전센터");
    }

    @Test
    void normalizesSkuNotation() {
        assertThat(validator.validate("days_of_supply", args("SKU-7", "", 0, "", "", "value", 0, "")).call().sku())
                .isEqualTo("SKU-007");
        assertThat(validator.validate("days_of_supply", args(" sku-07 ", "", 0, "", "", "value", 0, "")).call().sku())
                .isEqualTo("SKU-007");
    }

    @Test
    void ignoresArgumentsTheToolDoesNotUse() {
        ToolValidation result = validator.validate("days_of_supply",
                args("SKU-007", "", 999, "not-a-date", "", "value", 99, "sideways"));

        assertThat(result.ok()).isTrue();
        assertThat(result.call()).isEqualTo(new ToolCall(ToolName.DAYS_OF_SUPPLY, ToolOutput.VALUE,
                "SKU-007", null, null, null, null, null, null));
    }

    @Test
    void rejectsUnknownTool() {
        assertRejected(validator.validate("inventory_turnover", args("", "", 0, "", "", "value", 0, "")), "unknown tool");
        assertRejected(validator.validate("", args("", "", 0, "", "", "value", 0, "")), "unknown tool");
    }

    @Test
    void rejectsMalformedSku() {
        assertRejected(validator.validate("days_of_supply", args("007", "", 0, "", "", "value", 0, "")), "sku must look like");
        assertRejected(validator.validate("days_of_supply", args("SKU-0007", "", 0, "", "", "value", 0, "")),
                "sku must look like");
        assertRejected(validator.validate("days_of_supply", args("SKU-007' OR '1'='1", "", 0, "", "", "value", 0, "")),
                "sku must look like");
    }

    @Test
    void rejectsUnknownSku() {
        assertRejected(validator.validate("days_of_supply", args("SKU-999", "", 0, "", "", "value", 0, "")), "unknown sku");
    }

    @Test
    void rejectsUnknownLocation() {
        assertRejected(validator.validate("days_of_supply", args("", "서울", 0, "", "", "value", 0, "")), "unknown location");
        assertRejected(validator.validate("days_of_supply", args("", "LOC-01", 0, "", "", "value", 0, "")),
                "unknown location");
    }

    @Test
    void rejectsOutputTheToolDoesNotSupport() {
        assertRejected(validator.validate("days_of_supply", args("", "", 0, "", "", "count", 0, "")), "output must be one of");
        assertRejected(validator.validate("reorder_needed", args("", "", 0, "", "", "ranking", 3, "asc")),
                "output must be one of");
        assertRejected(validator.validate("days_of_supply", args("", "", 0, "", "", "", 0, "")), "output must be one of");
    }

    @Test
    void rejectsRankingWithoutValidTopN() {
        assertRejected(validator.validate("days_of_supply", args("", "", 0, "", "", "ranking", 0, "asc")), "top_n must be");
        assertRejected(validator.validate("days_of_supply", args("", "", 0, "", "", "ranking", 51, "asc")), "top_n must be");
    }

    @Test
    void rejectsRankingWithoutOrder() {
        assertRejected(validator.validate("days_of_supply", args("", "", 0, "", "", "ranking", 5, "")), "order must be");
        assertRejected(validator.validate("days_of_supply", args("", "", 0, "", "", "ranking", 5, "lowest")),
                "order must be");
    }

    @Test
    void rejectsRankingWithSku() {
        assertRejected(validator.validate("days_of_supply", args("SKU-007", "", 0, "", "", "ranking", 5, "asc")),
                "ranking does not take a sku");
    }

    @Test
    void rejectsDaysOutOfRange() {
        assertRejected(validator.validate("avg_daily_sales", args("", "", 0, "", "", "value", 0, "")), "days must be 1..91");
        assertRejected(validator.validate("avg_daily_sales", args("", "", 92, "", "", "value", 0, "")), "days must be 1..91");
        assertThat(validator.validate("avg_daily_sales", args("", "", 91, "", "", "value", 0, "")).ok()).isTrue();
    }

    @Test
    void rejectsHalfOpenPeriod() {
        assertRejected(validator.validate("stockout_rate", args("", "", 0, "2026-10-01", "", "value", 0, "")),
                "must be given together");
        assertRejected(validator.validate("stockout_rate", args("", "", 0, "", "2026-10-31", "value", 0, "")),
                "must be given together");
    }

    @Test
    void rejectsMalformedOrReversedPeriod() {
        assertRejected(validator.validate("stockout_rate", args("", "", 0, "10/01", "10/31", "value", 0, "")),
                "must be yyyy-MM-dd");
        assertRejected(validator.validate("stockout_rate", args("", "", 0, "2026-10-31", "2026-10-01", "value", 0, "")),
                "is after end_date");
    }

    @Test
    void rejectsPeriodOutsideTheData() {
        assertRejected(validator.validate("stockout_rate", args("", "", 0, "2026-08-09", "2026-08-31", "value", 0, "")),
                "period must be within");
        assertRejected(validator.validate("stockout_rate", args("", "", 0, "2026-11-01", "2026-11-09", "value", 0, "")),
                "period must be within");
    }

    @Test
    void acceptsRegionCategoryAndAbcClassFilters() {
        ToolValidation value = validator.validate("avg_daily_sales", filtered("", "", "영남권", "음료", "A", 28, "value", 0, ""));
        ToolValidation ranking = validator.validate("stockout_rate", filtered("", "", "", "스낵", "", 0, "ranking", 3, "desc"));
        ToolValidation count = validator.validate("reorder_needed", filtered("", "서울센터", "", "", "C", 0, "count", 0, ""));

        assertThat(value.call()).isEqualTo(new ToolCall(ToolName.AVG_DAILY_SALES, ToolOutput.VALUE, null, null, 28,
                null, null, null, null, "영남권", "음료", "A"));
        assertThat(value.call().toArgsMap()).containsExactly(
                java.util.Map.entry("region", "영남권"), java.util.Map.entry("category", "음료"),
                java.util.Map.entry("abc_class", "A"), java.util.Map.entry("days", 28),
                java.util.Map.entry("output", "value"));
        assertThat(ranking.ok()).isTrue();
        assertThat(ranking.call().category()).isEqualTo("스낵");
        assertThat(count.ok()).isTrue();
        assertThat(count.call().abcClass()).isEqualTo("C");
    }

    @Test
    void rejectsUnknownFilterValues() {
        assertRejected(validator.validate("days_of_supply", filtered("", "", "강원권", "", "", 0, "value", 0, "")), "unknown region");
        assertRejected(validator.validate("days_of_supply", filtered("", "", "", "가전", "", 0, "value", 0, "")), "unknown category");
        assertRejected(validator.validate("days_of_supply", filtered("", "", "", "", "D", 0, "value", 0, "")), "unknown abc_class");
        assertRejected(validator.validate("days_of_supply", filtered("", "", "", "", "a", 0, "value", 0, "")), "unknown abc_class");
        assertRejected(validator.validate("days_of_supply", filtered("", "", "", "음료' OR '1'='1", "", 0, "value", 0, "")),
                "unknown category");
    }

    @Test
    void rejectsContradictoryFilterCombinations() {
        assertRejected(validator.validate("days_of_supply", filtered("", "서울센터", "수도권", "", "", 0, "value", 0, "")),
                "location and region cannot be given together");
        assertRejected(validator.validate("days_of_supply", filtered("SKU-007", "", "", "음료", "", 0, "value", 0, "")),
                "sku cannot be combined");
        assertRejected(validator.validate("days_of_supply", filtered("SKU-007", "", "", "", "A", 0, "value", 0, "")),
                "sku cannot be combined");
        assertThat(validator.validate("days_of_supply", filtered("SKU-007", "", "수도권", "", "", 0, "value", 0, "")).ok())
                .isTrue();
    }

    @Test
    void callWithoutFiltersIsTheSameAsBefore() {
        ToolValidation result = validator.validate("days_of_supply", args("SKU-007", "서울센터", 0, "", "", "value", 0, ""));

        assertThat(result.call().region()).isNull();
        assertThat(result.call().category()).isNull();
        assertThat(result.call().abcClass()).isNull();
        assertThat(result.call().toArgsMap()).containsOnlyKeys("sku", "location", "output");
    }

    private static RawToolArgs filtered(String sku, String location, String region, String category, String abcClass,
            int days, String output, int topN, String order) {
        return new RawToolArgs(sku, location, days, "", "", output, topN, order, region, category, abcClass);
    }

    private static RawToolArgs args(String sku, String location, int days, String startDate, String endDate,
            String output, int topN, String order) {
        return new RawToolArgs(sku, location, days, startDate, endDate, output, topN, order);
    }

    private static void assertRejected(ToolValidation result, String reasonPart) {
        assertThat(result.ok()).isFalse();
        assertThat(result.call()).isNull();
        assertThat(result.reason()).contains(reasonPart);
    }
}
