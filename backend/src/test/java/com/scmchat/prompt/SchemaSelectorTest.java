package com.scmchat.prompt;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class SchemaSelectorTest {

    private final SchemaSelector selector = new SchemaSelector(Path.of("../prompts/schema_rules.yml"));

    @Test
    void selectsSalesForSalesKeywords() {
        assertThat(selector.select("어제 판매량은?")).containsExactlyInAnyOrder("sales_daily", "product", "location");
        assertThat(selector.select("이번 주 출고량")).contains("sales_daily");
    }

    @Test
    void selectsInventoryForStockKeywords() {
        assertThat(selector.select("현재 재고는?")).containsExactlyInAnyOrder("inventory_daily", "product", "location");
        assertThat(selector.select("결품이 있는 곳")).contains("inventory_daily");
    }

    @Test
    void selectsForecastAndSalesForAccuracyKeywords() {
        assertThat(selector.select("예측 정확도가 낮은 것"))
                .containsExactlyInAnyOrder("forecast_weekly", "sales_daily", "product", "location");
    }

    @Test
    void selectsPolicyAndInventoryForSafetyStockKeywords() {
        assertThat(selector.select("안전재고 미달"))
                .containsExactlyInAnyOrder("supply_policy", "inventory_daily", "product", "location");
        assertThat(selector.select("리드타임이 긴 것")).contains("supply_policy");
    }

    @Test
    void selectsOnlyDimensionTablesWithoutAddingFacts() {
        assertThat(selector.select("카테고리 목록")).containsExactly("product");
        assertThat(selector.select("센터는 어느 지역에 있어?")).containsExactly("location");
    }

    @Test
    void addsBothDimensionsWhenAnyFactTableIsSelected() {
        assertThat(selector.select("판매 추이")).contains("product", "location");
    }

    @Test
    void combinesTablesOfSeveralRules() {
        assertThat(selector.select("서울센터 SKU-026의 재고와 판매"))
                .containsExactlyInAnyOrder("inventory_daily", "sales_daily", "product", "location");
    }

    @Test
    void fallsBackToAllTablesWhenNoKeywordMatches() {
        assertThat(selector.select("안녕하세요")).containsExactlyInAnyOrderElementsOf(SchemaCatalog.tables());
    }

    @Test
    void matchesKeywordsCaseInsensitively() {
        assertThat(selector.select("wmape 높은 sku")).contains("forecast_weekly", "product");
    }
}
