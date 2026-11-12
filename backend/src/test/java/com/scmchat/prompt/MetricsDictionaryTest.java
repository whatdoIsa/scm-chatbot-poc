package com.scmchat.prompt;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class MetricsDictionaryTest {

    // Gradle은 backend/를 작업 디렉터리로 테스트를 실행한다.
    private final MetricsDictionary dictionary = new MetricsDictionary(Path.of("../prompts/metrics.yml"));

    @Test
    void readsAsOfDate() {
        assertThat(dictionary.asOfDate()).isEqualTo(LocalDate.of(2026, 11, 8));
    }

    @Test
    void rendersAllDateRules() {
        assertThat(dictionary.dateRules().lines()).hasSize(9)
                .contains("- 어제: 2026-11-07", "- 이번 주: 2026-11-02 ~ 2026-11-08", "- 지난달: 2026-10-01 ~ 2026-10-31");
    }

    @Test
    void fullSelectionContainsEveryTermAndMetric() {
        String all = dictionary.select("아무 질문", false);

        assertThat(all.lines().filter(line -> line.startsWith("- "))).hasSize(3 + 12 + 6);
    }

    @Test
    void prunedSelectionKeepsOnlyMentionedTermsAndMetrics() {
        String pruned = dictionary.select("서울센터 SKU-026의 재고보유일수는?", true);

        assertThat(pruned).contains("Unit rules:", "- 재고보유일수: ", "재고, 현재고 -> ", "거점, 센터, 창고 -> ");
        assertThat(pruned).doesNotContain("결품률", "예측 정확도", "재주문 필요", "리드타임");
    }

    @Test
    void prunedSelectionIgnoresSpacesInKeywords() {
        assertThat(dictionary.select("입고예정 수량은?", true)).contains("inventory_daily.in_transit");
        assertThat(dictionary.select("예측정확도가 낮은 품목", true)).contains("- 예측 정확도: ");
    }

    @Test
    void prunedSelectionWithoutKeywordsKeepsUnitRulesOnly() {
        String pruned = dictionary.select("안녕하세요", true);

        assertThat(pruned).contains("Unit rules:").doesNotContain("Terms:", "Metrics:");
    }

    @Test
    void reportsTablesNeededByMentionedMetrics() {
        assertThat(dictionary.tablesFor("예측 정확도가 낮은 SKU")).containsExactlyInAnyOrder("forecast_weekly", "sales_daily");
        assertThat(dictionary.tablesFor("재주문이 필요한 품목")).containsExactlyInAnyOrder("inventory_daily", "supply_policy");
        assertThat(dictionary.tablesFor("안녕하세요")).isEmpty();
    }
}
