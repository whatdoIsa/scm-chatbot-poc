package com.scmchat.tool;

import com.scmchat.config.ChatProperties;
import com.scmchat.executor.QueryResult;
import com.scmchat.executor.SqlExecutionException;
import com.scmchat.mapper.MetricToolMapper;
import com.scmchat.mapper.ToolQuery;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/**
 * 검증된 도구 호출을 MyBatis 고정 쿼리로 실행하고 API_SPEC 8.4의 컬럼 순서로 돌려준다.
 * 날짜 범위와 상한은 여기서 정한다. 모델이 정하는 것은 ToolCall에 담긴 값뿐이다.
 */
@Component
public class ToolExecutor {

    /** 재고보유일수의 분모가 되는 판매 기간(PRD 5.3). */
    static final int DAYS_OF_SUPPLY_WINDOW = 28;

    private final MetricToolMapper mapper;
    private final LocalDate dataStartDate;
    private final LocalDate asOfDate;
    private final int listLimit;

    public ToolExecutor(MetricToolMapper mapper, ChatProperties chatProperties) {
        this.mapper = mapper;
        this.dataStartDate = chatProperties.dataStartDate();
        this.asOfDate = chatProperties.asOfDate();
        this.listLimit = chatProperties.validatorLimit();
    }

    public QueryResult execute(ToolCall call) {
        List<String> columns = columnsOf(call.tool(), call.output());
        List<Map<String, Object>> fetched;
        try {
            fetched = fetch(call);
        } catch (DataAccessException e) {
            throw SqlExecutionException.from(e);
        }

        List<List<Object>> rows = new ArrayList<>(fetched.size());
        for (Map<String, Object> row : fetched) {
            List<Object> values = new ArrayList<>(columns.size());
            for (String column : columns) {
                if (!row.containsKey(column)) {
                    throw new IllegalStateException(
                            call.tool().value() + " " + call.output().value() + " did not return column " + column);
                }
                values.add(row.get(column));
            }
            rows.add(values);
        }
        // 상한을 채운 목록은 더 있었을 수 있으므로 잘린 것으로 표시한다.
        boolean truncated = call.output() == ToolOutput.LIST && rows.size() >= listLimit;
        return new QueryResult(columns, rows, truncated);
    }

    private List<Map<String, Object>> fetch(ToolCall call) {
        boolean ranking = call.output() == ToolOutput.RANKING;
        return switch (call.tool()) {
            case AVG_DAILY_SALES -> {
                ToolQuery query = query(call, asOfDate.minusDays(call.days() - 1L), asOfDate, call.days());
                yield ranking ? mapper.avgDailySalesRanking(query) : mapper.avgDailySalesValue(query);
            }
            case DAYS_OF_SUPPLY -> {
                ToolQuery query = query(call, asOfDate.minusDays(DAYS_OF_SUPPLY_WINDOW - 1L), asOfDate,
                        DAYS_OF_SUPPLY_WINDOW);
                yield ranking ? mapper.daysOfSupplyRanking(query) : mapper.daysOfSupplyValue(query);
            }
            case STOCKOUT_RATE -> {
                ToolQuery query = query(call,
                        call.startDate() == null ? dataStartDate : call.startDate(),
                        call.endDate() == null ? asOfDate : call.endDate(), null);
                yield ranking ? mapper.stockoutRateRanking(query) : mapper.stockoutRateValue(query);
            }
            case FORECAST_ACCURACY -> {
                ToolQuery query = query(call, null, null, null);
                yield ranking ? mapper.forecastAccuracyRanking(query) : mapper.forecastAccuracyValue(query);
            }
            case BELOW_SAFETY_STOCK -> {
                ToolQuery query = query(call, null, null, null);
                yield call.output() == ToolOutput.COUNT
                        ? mapper.belowSafetyStockCount(query) : mapper.belowSafetyStockList(query);
            }
            case REORDER_NEEDED -> {
                ToolQuery query = query(call, null, null, null);
                yield call.output() == ToolOutput.COUNT
                        ? mapper.reorderNeededCount(query) : mapper.reorderNeededList(query);
            }
        };
    }

    private ToolQuery query(ToolCall call, LocalDate fromDate, LocalDate toDate, Integer days) {
        Integer limit = switch (call.output()) {
            case RANKING -> call.topN();
            case LIST -> listLimit;
            default -> null;
        };
        return new ToolQuery(call.sku(), call.location(), asOfDate, fromDate, toDate, days, limit,
                Boolean.TRUE.equals(call.ascending()));
    }

    /**
     * 도구는 자기 지표 값만 돌려준다. 재고나 일평균 같은 구성 요소를 함께 내면, 다른 지표를 물은 질문에
     * 도구를 잘못 골라도 채점의 추가 컬럼 허용 규칙(PRD 5.4) 때문에 정답으로 처리될 수 있다.
     */
    static List<String> columnsOf(ToolName tool, ToolOutput output) {
        return switch (tool) {
            case AVG_DAILY_SALES -> output == ToolOutput.RANKING
                    ? List.of("sku", "avg_daily_qty") : List.of("avg_daily_qty");
            case DAYS_OF_SUPPLY -> output == ToolOutput.RANKING
                    ? List.of("sku", "days_of_supply") : List.of("days_of_supply");
            case STOCKOUT_RATE -> output == ToolOutput.RANKING
                    ? List.of("sku", "stockout_rate") : List.of("stockout_rate");
            case FORECAST_ACCURACY -> output == ToolOutput.RANKING
                    ? List.of("sku", "accuracy") : List.of("accuracy");
            case BELOW_SAFETY_STOCK -> output == ToolOutput.COUNT
                    ? List.of("cnt") : List.of("sku", "location_name", "on_hand", "safety_stock");
            case REORDER_NEEDED -> output == ToolOutput.COUNT
                    ? List.of("cnt") : List.of("sku", "location_name", "on_hand", "in_transit", "reorder_point");
        };
    }
}
