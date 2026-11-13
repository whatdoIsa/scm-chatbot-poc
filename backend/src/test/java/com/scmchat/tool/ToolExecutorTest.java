package com.scmchat.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.scmchat.config.ChatProperties;
import com.scmchat.executor.QueryResult;
import com.scmchat.executor.SqlExecutionException;
import com.scmchat.mapper.MetricToolMapper;
import com.scmchat.mapper.ToolQuery;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;

/** 날짜 범위와 상한 계산, 컬럼 순서, 잘림 표시처럼 DB 없이 볼 수 있는 부분을 확인한다. 쿼리 자체는 MetricToolIT가 본다. */
class ToolExecutorTest {

    private static final int LIST_LIMIT = 200;

    private final MetricToolMapper mapper = mock(MetricToolMapper.class);
    private final ToolExecutor executor = new ToolExecutor(mapper, new ChatProperties(
            "v3", LocalDate.of(2026, 11, 8), LocalDate.of(2026, 8, 10), 1000, LIST_LIMIT, "../prompts",
            new ChatProperties.Llm("fake", "http://localhost:11434", "qwen2.5-coder:7b", 0, 42, 8192, 120)));

    @Test
    void avgDailySalesUsesTheLastNDaysEndingOnTheAsOfDate() {
        when(mapper.avgDailySalesValue(any())).thenReturn(List.of(row("avg_daily_qty", 12.5)));

        executor.execute(new ToolCall(ToolName.AVG_DAILY_SALES, ToolOutput.VALUE, "SKU-007", null, 7,
                null, null, null, null));

        ArgumentCaptor<ToolQuery> captor = ArgumentCaptor.forClass(ToolQuery.class);
        verify(mapper).avgDailySalesValue(captor.capture());
        assertThat(captor.getValue()).isEqualTo(new ToolQuery("SKU-007", null,
                LocalDate.of(2026, 11, 8), LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 8), 7, null, false));
    }

    @Test
    void daysOfSupplyUsesTheLast28Days() {
        when(mapper.daysOfSupplyRanking(any())).thenReturn(List.of());

        executor.execute(new ToolCall(ToolName.DAYS_OF_SUPPLY, ToolOutput.RANKING, null, "서울센터", null,
                null, null, 5, true));

        ArgumentCaptor<ToolQuery> captor = ArgumentCaptor.forClass(ToolQuery.class);
        verify(mapper).daysOfSupplyRanking(captor.capture());
        assertThat(captor.getValue()).isEqualTo(new ToolQuery(null, "서울센터", LocalDate.of(2026, 11, 8),
                LocalDate.of(2026, 10, 12), LocalDate.of(2026, 11, 8), 28, 5, true));
    }

    @Test
    void stockoutRateDefaultsToTheWholeDataPeriod() {
        when(mapper.stockoutRateValue(any())).thenReturn(List.of(row("stockout_rate", 0.1)));

        executor.execute(new ToolCall(ToolName.STOCKOUT_RATE, ToolOutput.VALUE, null, null, null,
                null, null, null, null));
        executor.execute(new ToolCall(ToolName.STOCKOUT_RATE, ToolOutput.VALUE, null, null, null,
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), null, null));

        ArgumentCaptor<ToolQuery> captor = ArgumentCaptor.forClass(ToolQuery.class);
        verify(mapper, org.mockito.Mockito.times(2)).stockoutRateValue(captor.capture());
        assertThat(captor.getAllValues().get(0).fromDate()).isEqualTo(LocalDate.of(2026, 8, 10));
        assertThat(captor.getAllValues().get(0).toDate()).isEqualTo(LocalDate.of(2026, 11, 8));
        assertThat(captor.getAllValues().get(1).fromDate()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(captor.getAllValues().get(1).toDate()).isEqualTo(LocalDate.of(2026, 10, 31));
    }

    @Test
    void returnsColumnsInSpecOrderWhateverOrderTheRowHas() {
        Map<String, Object> shuffled = new LinkedHashMap<>();
        shuffled.put("reorder_point", 40);
        shuffled.put("on_hand", 10);
        shuffled.put("sku", "SKU-007");
        shuffled.put("in_transit", 5);
        shuffled.put("location_name", "서울센터");
        when(mapper.reorderNeededList(any())).thenReturn(List.of(shuffled));

        QueryResult result = executor.execute(new ToolCall(ToolName.REORDER_NEEDED, ToolOutput.LIST, null, null,
                null, null, null, null, null));

        assertThat(result.columns()).containsExactly("sku", "location_name", "on_hand", "in_transit", "reorder_point");
        assertThat(result.rows()).containsExactly(List.of("SKU-007", "서울센터", 10, 5, 40));
    }

    @Test
    void marksAListTruncatedOnlyWhenItFillsTheLimit() {
        ToolCall call = new ToolCall(ToolName.BELOW_SAFETY_STOCK, ToolOutput.LIST, null, null, null,
                null, null, null, null);
        when(mapper.belowSafetyStockList(any())).thenReturn(listRows(LIST_LIMIT), listRows(LIST_LIMIT - 1));

        assertThat(executor.execute(call).truncated()).isTrue();
        assertThat(executor.execute(call).truncated()).isFalse();

        ArgumentCaptor<ToolQuery> captor = ArgumentCaptor.forClass(ToolQuery.class);
        verify(mapper, org.mockito.Mockito.times(2)).belowSafetyStockList(captor.capture());
        assertThat(captor.getValue().limit()).isEqualTo(LIST_LIMIT);
    }

    @Test
    void keepsANullMetricValueAsARow() {
        Map<String, Object> nullValue = new LinkedHashMap<>();
        nullValue.put("accuracy", null);
        when(mapper.forecastAccuracyValue(any())).thenReturn(List.of(nullValue));

        QueryResult result = executor.execute(new ToolCall(ToolName.FORECAST_ACCURACY, ToolOutput.VALUE, "SKU-007",
                null, null, null, null, null, null));

        assertThat(result.rows()).hasSize(1);
        assertThat(result.rows().get(0)).containsExactly((Object) null);
    }

    @Test
    void failsLoudlyWhenTheQueryDoesNotReturnASpecColumn() {
        when(mapper.belowSafetyStockCount(any())).thenReturn(List.of(row("count", 3)));

        assertThatThrownBy(() -> executor.execute(new ToolCall(ToolName.BELOW_SAFETY_STOCK, ToolOutput.COUNT, null,
                null, null, null, null, null, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("did not return column cnt");
    }

    @Test
    void turnsDatabaseFailureIntoSqlExecutionException() {
        when(mapper.reorderNeededCount(any())).thenThrow(new DataAccessResourceFailureException("wrapped",
                new SQLException("ERROR: canceling statement due to statement timeout", "57014")));

        assertThatThrownBy(() -> executor.execute(new ToolCall(ToolName.REORDER_NEEDED, ToolOutput.COUNT, null,
                null, null, null, null, null, null)))
                .isInstanceOf(SqlExecutionException.class)
                .hasMessageContaining("statement timeout")
                .extracting(e -> ((SqlExecutionException) e).getSqlState())
                .isEqualTo("57014");
    }

    private static Map<String, Object> row(String column, Object value) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put(column, value);
        return row;
    }

    private static List<Map<String, Object>> listRows(int count) {
        List<Map<String, Object>> rows = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("sku", "SKU-" + i);
            row.put("location_name", "서울센터");
            row.put("on_hand", 1);
            row.put("safety_stock", 2);
            rows.add(row);
        }
        return rows;
    }
}
