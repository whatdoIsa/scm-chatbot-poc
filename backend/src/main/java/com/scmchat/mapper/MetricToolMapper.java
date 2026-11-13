package com.scmchat.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

/**
 * 조회 도구의 고정 쿼리(PRD 10.4). SQL은 resources/mapper/scmro/MetricToolMapper.xml에 있고 scm_ro로 실행된다.
 * 행은 컬럼 순서를 지키는 맵으로 받고, ToolExecutor가 API_SPEC 8.4의 컬럼 순서로 옮긴다.
 */
@ScmRoMapper
public interface MetricToolMapper {

    List<String> locationNames();

    List<String> regions();

    List<String> categories();

    boolean skuExists(@Param("sku") String sku);

    List<Map<String, Object>> avgDailySalesValue(ToolQuery query);

    List<Map<String, Object>> avgDailySalesRanking(ToolQuery query);

    List<Map<String, Object>> daysOfSupplyValue(ToolQuery query);

    List<Map<String, Object>> daysOfSupplyRanking(ToolQuery query);

    List<Map<String, Object>> stockoutRateValue(ToolQuery query);

    List<Map<String, Object>> stockoutRateRanking(ToolQuery query);

    List<Map<String, Object>> forecastAccuracyValue(ToolQuery query);

    List<Map<String, Object>> forecastAccuracyRanking(ToolQuery query);

    List<Map<String, Object>> belowSafetyStockList(ToolQuery query);

    List<Map<String, Object>> belowSafetyStockCount(ToolQuery query);

    List<Map<String, Object>> reorderNeededList(ToolQuery query);

    List<Map<String, Object>> reorderNeededCount(ToolQuery query);
}
