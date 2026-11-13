package com.scmchat.tool;

import static org.assertj.core.api.Assertions.assertThat;

import com.scmchat.eval.ResultComparator;
import com.scmchat.executor.QueryResult;
import com.scmchat.executor.ReadOnlySqlExecutor;
import com.scmchat.mapper.EvalSummaryMapper;
import com.scmchat.mapper.MetricToolMapper;
import com.scmchat.mapper.ToolQuery;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 조회 도구의 결과를, 매퍼 XML과 따로 쓴 참조 SQL의 결과와 실제 DB에서 비교한다(FR-15).
 * 참조 SQL은 거점을 location_id로 직접 지정하고 날짜를 리터럴로 적어, 도구가 날짜와 조인을 맞게 만드는지도 함께 본다.
 */
@Tag("integration")
@SpringBootTest
class MetricToolIT {

    private static final String WEEKLY = "WITH w AS (SELECT f.sku, f.location_id, f.week_start, f.forecast_qty,"
            + " (SELECT COALESCE(SUM(s.qty), 0) FROM scm.sales_daily s WHERE s.sku = f.sku"
            + " AND s.location_id = f.location_id AND s.sale_date >= f.week_start"
            + " AND s.sale_date < f.week_start + 7) AS actual FROM scm.forecast_weekly f) ";

    @Autowired
    private ToolArgsValidator validator;

    @Autowired
    private ToolExecutor toolExecutor;

    @Autowired
    private MetricToolMapper metricToolMapper;

    @Autowired
    private ReadOnlySqlExecutor sqlExecutor;

    @Autowired
    @Qualifier("scmRoSqlSessionFactory")
    private SqlSessionFactory scmRoSqlSessionFactory;

    @Autowired
    @Qualifier("sqlSessionFactory")
    private SqlSessionFactory appSqlSessionFactory;

    private final ResultComparator comparator = new ResultComparator();

    @Test
    void avgDailySalesValue() {
        assertMatches(call("avg_daily_sales", args("SKU-029", "", 28, "", "", "value", 0, "")), false,
                "SELECT SUM(qty)::numeric / 28 AS avg_daily_qty FROM scm.sales_daily"
                        + " WHERE sku = 'SKU-029' AND sale_date BETWEEN DATE '2026-10-12' AND DATE '2026-11-08'");
        assertMatches(call("avg_daily_sales", args("", "대전센터", 14, "", "", "value", 0, "")), false,
                "SELECT SUM(qty)::numeric / 14 AS avg_daily_qty FROM scm.sales_daily"
                        + " WHERE location_id = 'LOC-03' AND sale_date BETWEEN DATE '2026-10-26' AND DATE '2026-11-08'");
        assertMatches(call("avg_daily_sales", args("", "", 1, "", "", "value", 0, "")), false,
                "SELECT SUM(qty)::numeric AS avg_daily_qty FROM scm.sales_daily WHERE sale_date = DATE '2026-11-08'");
    }

    @Test
    void avgDailySalesRanking() {
        assertMatches(call("avg_daily_sales", args("", "", 7, "", "", "ranking", 3, "desc")), true,
                "SELECT sku, SUM(qty)::numeric / 7 AS avg_daily_qty FROM scm.sales_daily"
                        + " WHERE sale_date BETWEEN DATE '2026-11-02' AND DATE '2026-11-08'"
                        + " GROUP BY sku ORDER BY 2 DESC, sku LIMIT 3");
        assertMatches(call("avg_daily_sales", args("", "부산센터", 14, "", "", "ranking", 4, "asc")), true,
                "SELECT sku, SUM(qty)::numeric / 14 AS avg_daily_qty FROM scm.sales_daily"
                        + " WHERE location_id = 'LOC-02' AND sale_date BETWEEN DATE '2026-10-26' AND DATE '2026-11-08'"
                        + " GROUP BY sku ORDER BY 2 ASC, sku LIMIT 4");
    }

    @Test
    void daysOfSupplyValue() {
        assertMatches(call("days_of_supply", args("SKU-026", "서울센터", 0, "", "", "value", 0, "")), false,
                "SELECT i.on_hand / d.daily AS days_of_supply"
                        + " FROM scm.inventory_daily i, (SELECT SUM(qty)::numeric / 28 AS daily FROM scm.sales_daily"
                        + " WHERE sku = 'SKU-026' AND location_id = 'LOC-01'"
                        + " AND sale_date BETWEEN DATE '2026-10-12' AND DATE '2026-11-08') d"
                        + " WHERE i.sku = 'SKU-026' AND i.location_id = 'LOC-01' AND i.snap_date = DATE '2026-11-08'");
        // SKU 단위는 분자와 분모를 모두 거점 합산한다.
        assertMatches(call("days_of_supply", args("SKU-021", "", 0, "", "", "value", 0, "")), false,
                "SELECT (SELECT SUM(on_hand) FROM scm.inventory_daily WHERE sku = 'SKU-021'"
                        + " AND snap_date = DATE '2026-11-08')::numeric * 28"
                        + " / (SELECT SUM(qty) FROM scm.sales_daily WHERE sku = 'SKU-021'"
                        + " AND sale_date BETWEEN DATE '2026-10-12' AND DATE '2026-11-08') AS days_of_supply");
    }

    @Test
    void daysOfSupplyRanking() {
        String perSku = "WITH oh AS (SELECT sku, SUM(on_hand)::numeric AS on_hand FROM scm.inventory_daily"
                + " WHERE snap_date = DATE '2026-11-08' %1$s GROUP BY sku), d AS (SELECT sku, SUM(qty)::numeric / 28 AS daily"
                + " FROM scm.sales_daily WHERE sale_date BETWEEN DATE '2026-10-12' AND DATE '2026-11-08' %1$s GROUP BY sku)"
                + " SELECT oh.sku, oh.on_hand / d.daily AS days_of_supply FROM oh JOIN d ON d.sku = oh.sku"
                + " WHERE d.daily > 0 ORDER BY 2 %2$s, oh.sku LIMIT %3$d";
        assertMatches(call("days_of_supply", args("", "", 0, "", "", "ranking", 5, "asc")), true,
                String.format(perSku, "", "ASC", 5));
        assertMatches(call("days_of_supply", args("", "서울센터", 0, "", "", "ranking", 3, "desc")), true,
                String.format(perSku, "AND location_id = 'LOC-01'", "DESC", 3));
    }

    @Test
    void stockoutRateValue() {
        assertMatches(call("stockout_rate", args("SKU-036", "서울센터", 0, "", "", "value", 0, "")), false,
                "SELECT COUNT(*) FILTER (WHERE on_hand = 0)::numeric / COUNT(*) AS stockout_rate"
                        + " FROM scm.inventory_daily WHERE sku = 'SKU-036' AND location_id = 'LOC-01'");
        assertMatches(call("stockout_rate", args("SKU-023", "", 0, "2026-10-01", "2026-10-31", "value", 0, "")), false,
                "SELECT COUNT(*) FILTER (WHERE on_hand = 0)::numeric / COUNT(*) AS stockout_rate"
                        + " FROM scm.inventory_daily WHERE sku = 'SKU-023'"
                        + " AND snap_date >= DATE '2026-10-01' AND snap_date < DATE '2026-11-01'");
    }

    @Test
    void stockoutRateRanking() {
        assertMatches(call("stockout_rate", args("", "", 0, "", "", "ranking", 5, "desc")), true,
                "SELECT sku, COUNT(*) FILTER (WHERE on_hand = 0)::numeric / COUNT(*) AS stockout_rate"
                        + " FROM scm.inventory_daily GROUP BY sku ORDER BY 2 DESC, sku LIMIT 5");
        assertMatches(call("stockout_rate", args("", "광주센터", 0, "2026-10-01", "2026-10-31", "ranking", 2, "desc")), true,
                "SELECT sku, COUNT(*) FILTER (WHERE on_hand = 0)::numeric / COUNT(*) AS stockout_rate"
                        + " FROM scm.inventory_daily WHERE location_id = 'LOC-04'"
                        + " AND snap_date BETWEEN DATE '2026-10-01' AND DATE '2026-10-31'"
                        + " GROUP BY sku ORDER BY 2 DESC, sku LIMIT 2");
    }

    @Test
    void forecastAccuracyValue() {
        assertMatches(call("forecast_accuracy", args("SKU-008", "", 0, "", "", "value", 0, "")), false,
                WEEKLY + "SELECT 1 - SUM(ABS(actual - forecast_qty))::numeric / SUM(actual) AS accuracy"
                        + " FROM w WHERE sku = 'SKU-008'");
        assertMatches(call("forecast_accuracy", args("SKU-033", "광주센터", 0, "", "", "value", 0, "")), false,
                WEEKLY + "SELECT 1 - SUM(ABS(actual - forecast_qty))::numeric / SUM(actual) AS accuracy"
                        + " FROM w WHERE sku = 'SKU-033' AND location_id = 'LOC-04'");
    }

    @Test
    void forecastAccuracyRanking() {
        assertMatches(call("forecast_accuracy", args("", "", 0, "", "", "ranking", 4, "asc")), true,
                WEEKLY + "SELECT sku, 1 - SUM(ABS(actual - forecast_qty))::numeric / SUM(actual) AS accuracy"
                        + " FROM w GROUP BY sku ORDER BY 2 ASC, sku LIMIT 4");
        assertMatches(call("forecast_accuracy", args("", "대구센터", 0, "", "", "ranking", 3, "desc")), true,
                WEEKLY + "SELECT sku, 1 - SUM(ABS(actual - forecast_qty))::numeric / SUM(actual) AS accuracy"
                        + " FROM w WHERE location_id = 'LOC-05' GROUP BY sku ORDER BY 2 DESC, sku LIMIT 3");
    }

    @Test
    void belowSafetyStockListAndCount() {
        String from = " FROM scm.supply_policy p JOIN scm.inventory_daily i USING (sku, location_id)"
                + " JOIN scm.location l USING (location_id)"
                + " WHERE i.snap_date = DATE '2026-11-08' AND p.safety_stock > i.on_hand";
        assertMatches(call("below_safety_stock", args("", "", 0, "", "", "list", 0, "")), true,
                "SELECT sku, l.name AS location_name, i.on_hand, p.safety_stock" + from + " ORDER BY sku, l.name");
        assertMatches(call("below_safety_stock", args("", "부산센터", 0, "", "", "list", 0, "")), true,
                "SELECT sku, l.name AS location_name, i.on_hand, p.safety_stock" + from
                        + " AND location_id = 'LOC-02' ORDER BY sku");
        assertMatches(call("below_safety_stock", args("", "", 0, "", "", "count", 0, "")), false,
                "SELECT COUNT(*) AS cnt" + from);
    }

    @Test
    void reorderNeededListAndCount() {
        String from = " FROM scm.supply_policy p JOIN scm.inventory_daily i USING (sku, location_id)"
                + " JOIN scm.location l USING (location_id)"
                + " WHERE i.snap_date = DATE '2026-11-08' AND NOT (i.on_hand + i.in_transit > p.reorder_point)";
        assertMatches(call("reorder_needed", args("", "", 0, "", "", "list", 0, "")), true,
                "SELECT sku, l.name AS location_name, i.on_hand, i.in_transit, p.reorder_point" + from
                        + " ORDER BY sku, l.name");
        assertMatches(call("reorder_needed", args("", "대구센터", 0, "", "", "count", 0, "")), false,
                "SELECT COUNT(*) AS cnt" + from + " AND location_id = 'LOC-05'");
        assertMatches(call("reorder_needed", args("SKU-7", "", 0, "", "", "count", 0, "")), false,
                "SELECT COUNT(*) AS cnt" + from + " AND sku = 'SKU-007'");
    }

    /** 집계 대상이 없어 지표 값이 NULL이어도 1행이어야 한다(API_SPEC 8.4). MyBatis는 기본 설정에서 그런 행을 null로 돌려준다. */
    @Test
    void valueRowWhoseOnlyColumnIsNullIsStillARow() {
        ToolQuery noSuchSku = new ToolQuery("SKU-999", null, LocalDate.of(2026, 11, 8), LocalDate.of(2026, 11, 2),
                LocalDate.of(2026, 11, 8), 7, null, false);

        List<Map<String, Object>> rows = metricToolMapper.avgDailySalesValue(noSuchSku);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).isNotNull().containsEntry("avg_daily_qty", null);
        assertThat(metricToolMapper.forecastAccuracyValue(noSuchSku).get(0)).containsEntry("accuracy", null);
    }

    @Test
    void categoryAbcClassAndRegionFiltersMatchReferenceSql() {
        assertMatches(call("avg_daily_sales", filtered("", "", "음료", "", 28, "value", 0, "")), false,
                "SELECT SUM(s.qty)::numeric / 28 AS avg_daily_qty FROM scm.sales_daily s JOIN scm.product p USING (sku)"
                        + " WHERE p.category = '음료' AND s.sale_date BETWEEN DATE '2026-10-12' AND DATE '2026-11-08'");
        assertMatches(call("avg_daily_sales", filtered("", "", "스낵", "", 14, "ranking", 3, "desc")), true,
                "SELECT s.sku, SUM(s.qty)::numeric / 14 AS avg_daily_qty FROM scm.sales_daily s"
                        + " JOIN scm.product p USING (sku) WHERE p.category = '스낵'"
                        + " AND s.sale_date BETWEEN DATE '2026-10-26' AND DATE '2026-11-08'"
                        + " GROUP BY s.sku ORDER BY 2 DESC, s.sku LIMIT 3");
        assertMatches(call("stockout_rate", period("", "", "", "B", "2026-10-01", "2026-10-31")), false,
                "SELECT COUNT(*) FILTER (WHERE i.on_hand = 0)::numeric / COUNT(*) AS stockout_rate"
                        + " FROM scm.inventory_daily i JOIN scm.product p USING (sku) WHERE p.abc_class = 'B'"
                        + " AND i.snap_date BETWEEN DATE '2026-10-01' AND DATE '2026-10-31'");
        assertMatches(call("days_of_supply", filtered("", "영남권", "", "", 0, "value", 0, "")), false,
                "SELECT (SELECT SUM(on_hand) FROM scm.inventory_daily WHERE location_id IN ('LOC-02', 'LOC-05')"
                        + " AND snap_date = DATE '2026-11-08')::numeric * 28"
                        + " / (SELECT SUM(qty) FROM scm.sales_daily WHERE location_id IN ('LOC-02', 'LOC-05')"
                        + " AND sale_date BETWEEN DATE '2026-10-12' AND DATE '2026-11-08') AS days_of_supply");
        assertMatches(call("days_of_supply", filtered("", "수도권", "냉동식품", "", 0, "ranking", 3, "asc")), true,
                "WITH oh AS (SELECT i.sku, SUM(i.on_hand)::numeric AS on_hand FROM scm.inventory_daily i"
                        + " JOIN scm.product p USING (sku) WHERE i.snap_date = DATE '2026-11-08'"
                        + " AND i.location_id = 'LOC-01' AND p.category = '냉동식품' GROUP BY i.sku),"
                        + " d AS (SELECT sku, SUM(qty)::numeric / 28 AS daily FROM scm.sales_daily"
                        + " WHERE sale_date BETWEEN DATE '2026-10-12' AND DATE '2026-11-08' AND location_id = 'LOC-01'"
                        + " GROUP BY sku) SELECT oh.sku, oh.on_hand / d.daily AS days_of_supply FROM oh"
                        + " JOIN d ON d.sku = oh.sku WHERE d.daily > 0 ORDER BY 2 ASC, oh.sku LIMIT 3");
        assertMatches(call("forecast_accuracy", filtered("", "", "", "B", 0, "ranking", 3, "asc")), true,
                WEEKLY + "SELECT w.sku, 1 - SUM(ABS(actual - forecast_qty))::numeric / SUM(actual) AS accuracy"
                        + " FROM w JOIN scm.product p ON p.sku = w.sku WHERE p.abc_class = 'B'"
                        + " GROUP BY w.sku ORDER BY 2 ASC, w.sku LIMIT 3");
        assertMatches(call("forecast_accuracy", filtered("", "호남권", "음료", "A", 0, "value", 0, "")), false,
                WEEKLY + "SELECT 1 - SUM(ABS(actual - forecast_qty))::numeric / SUM(actual) AS accuracy"
                        + " FROM w JOIN scm.product p ON p.sku = w.sku WHERE p.abc_class = 'A' AND p.category = '음료'"
                        + " AND w.location_id = 'LOC-04'");
        String conditionFrom = " FROM scm.supply_policy p JOIN scm.inventory_daily i USING (sku, location_id)"
                + " JOIN scm.product pr USING (sku) JOIN scm.location l USING (location_id)"
                + " WHERE i.snap_date = DATE '2026-11-08'";
        assertMatches(call("below_safety_stock", filtered("", "", "냉동식품", "", 0, "count", 0, "")), false,
                "SELECT COUNT(*) AS cnt" + conditionFrom + " AND p.safety_stock > i.on_hand AND pr.category = '냉동식품'");
        assertMatches(call("reorder_needed", filtered("", "", "", "C", 0, "count", 0, "")), false,
                "SELECT COUNT(*) AS cnt" + conditionFrom
                        + " AND i.on_hand + i.in_transit <= p.reorder_point AND pr.abc_class = 'C'");
        assertMatches(call("reorder_needed", filtered("", "영남권", "", "C", 0, "list", 0, "")), true,
                "SELECT sku, l.name AS location_name, i.on_hand, i.in_transit, p.reorder_point" + conditionFrom
                        + " AND i.on_hand + i.in_transit <= p.reorder_point AND pr.abc_class = 'C'"
                        + " AND l.region = '영남권' ORDER BY sku, l.name");
    }

    @Test
    void validatorChecksFilterValuesAgainstTheDatabase() {
        assertThat(validator.validate("days_of_supply", filtered("", "충청권", "개인위생", "B", 0, "value", 0, "")).ok())
                .isTrue();
        assertThat(validator.validate("days_of_supply", filtered("", "강원권", "", "", 0, "value", 0, "")).reason())
                .contains("unknown region: 강원권");
        assertThat(validator.validate("days_of_supply", filtered("", "", "가전", "", 0, "value", 0, "")).reason())
                .contains("unknown category: 가전");
    }

    private static RawToolArgs filtered(String sku, String region, String category, String abcClass, int days,
            String output, int topN, String order) {
        return new RawToolArgs(sku, "", days, "", "", output, topN, order, region, category, abcClass);
    }

    private static RawToolArgs period(String sku, String region, String category, String abcClass, String startDate,
            String endDate) {
        return new RawToolArgs(sku, "", 0, startDate, endDate, "value", 0, "", region, category, abcClass);
    }

    @Test
    void emptyListKeepsItsColumns() {
        // 시드 데이터에서 대전센터에는 안전재고 미달이 없다.
        QueryResult list = toolExecutor.execute(call("below_safety_stock", args("", "대전센터", 0, "", "", "list", 0, "")));
        QueryResult count = toolExecutor.execute(call("below_safety_stock", args("", "대전센터", 0, "", "", "count", 0, "")));

        assertThat(list.columns()).containsExactly("sku", "location_name", "on_hand", "safety_stock");
        assertThat(list.rows()).isEmpty();
        assertThat(list.truncated()).isFalse();
        assertThat(count.rows()).hasSize(1);
        assertThat(((Number) count.rows().get(0).get(0)).longValue()).isZero();
    }

    @Test
    void listIsNotMarkedTruncatedBelowTheLimit() {
        QueryResult result = toolExecutor.execute(call("reorder_needed", args("", "", 0, "", "", "list", 0, "")));

        assertThat(result.rows()).isNotEmpty().hasSizeLessThan(200);
        assertThat(result.truncated()).isFalse();
    }

    @Test
    void validatorChecksSkuAndLocationAgainstTheDatabase() {
        assertThat(validator.validate("days_of_supply", args("SKU-050", "광주센터", 0, "", "", "value", 0, "")).ok())
                .isTrue();
        assertThat(validator.validate("days_of_supply", args("SKU-051", "", 0, "", "", "value", 0, "")).reason())
                .contains("unknown sku: SKU-051");
        assertThat(validator.validate("days_of_supply", args("", "인천센터", 0, "", "", "value", 0, "")).reason())
                .contains("unknown location: 인천센터");
    }

    @Test
    void toolQueriesRunAsTheReadOnlyRole() throws SQLException {
        try (SqlSession session = scmRoSqlSessionFactory.openSession()) {
            Connection connection = session.getConnection();
            try (Statement statement = connection.createStatement();
                    ResultSet resultSet = statement.executeQuery("SELECT current_user")) {
                assertThat(resultSet.next()).isTrue();
                assertThat(resultSet.getString(1)).isEqualTo("scm_ro");
            }
        }
        assertThat(scmRoSqlSessionFactory.getConfiguration().getDefaultStatementTimeout()).isEqualTo(5);
        // 조회 도구 매퍼는 scm_ro 팩토리에만, 평가 집계 매퍼는 app 팩토리에만 붙어 있어야 한다.
        assertThat(scmRoSqlSessionFactory.getConfiguration().hasMapper(MetricToolMapper.class)).isTrue();
        assertThat(scmRoSqlSessionFactory.getConfiguration().hasMapper(EvalSummaryMapper.class)).isFalse();
        assertThat(appSqlSessionFactory.getConfiguration().hasMapper(EvalSummaryMapper.class)).isTrue();
        assertThat(appSqlSessionFactory.getConfiguration().hasMapper(MetricToolMapper.class)).isFalse();
    }

    private ToolCall call(String toolName, RawToolArgs raw) {
        ToolValidation validation = validator.validate(toolName, raw);
        assertThat(validation.ok()).as(validation.reason()).isTrue();
        return validation.call();
    }

    /** 참조 SQL은 도구와 같은 컬럼을 돌려주게 써서, 양방향으로 비교해 모든 컬럼이 맞는지 본다. */
    private void assertMatches(ToolCall call, boolean orderSensitive, String referenceSql) {
        QueryResult expected = sqlExecutor.execute(referenceSql);
        QueryResult actual = toolExecutor.execute(call);

        assertThat(expected.rows()).as("reference returned no rows: " + referenceSql).isNotEmpty();
        assertThat(actual.columns()).as(call.toString()).isEqualTo(ToolExecutor.columnsOf(call.tool(), call.output()));
        assertThat(actual.columns()).as(call.toString()).hasSameSizeAs(expected.columns());
        ResultComparator.Comparison forward = comparator.compare(expected, actual, orderSensitive);
        ResultComparator.Comparison backward = comparator.compare(actual, expected, orderSensitive);
        assertThat(forward.match()).as(call + ": " + forward.reason()).isTrue();
        assertThat(backward.match()).as(call + ": " + backward.reason()).isTrue();
    }

    private static RawToolArgs args(String sku, String location, int days, String startDate, String endDate,
            String output, int topN, String order) {
        return new RawToolArgs(sku, location, days, startDate, endDate, output, topN, order);
    }
}
