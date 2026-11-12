# 평가 실행 51 (v2)

| 항목 | 값 |
| --- | --- |
| 모델 | qwen2.5-coder:7b |
| 프롬프트 버전 | v2 |
| temperature / seed | 0.00 / 42 |
| 반복 번호 | 1 |
| 골든셋 sha256 | 8ac43b1c501b6ca7a4c46bdc026554180041a9d36915cca6df7c3fee29a301f3 |
| 정확도 | 9 / 20 = 0.4500 |
| SQL 실행 성공률 | 0.7333 |
| 형식 준수율 | 1.0000 |
| 평균 응답 시간(ms) | 9843 |
| 평균 프롬프트 토큰 | 1872.1500 |
| 평균 출력 토큰 | 104.7000 |

## 유형별 정확도

| 유형 | 문항 | 정답 | 정확도 |
| --- | --- | --- | --- |
| PERIOD_AGG | 3 | 1 | 0.3333 |
| METRIC | 4 | 1 | 0.2500 |
| RANKING | 5 | 1 | 0.2000 |
| SIMPLE_LOOKUP | 3 | 2 | 0.6667 |
| AMBIGUOUS | 2 | 1 | 0.5000 |
| REFUSE | 3 | 3 | 1.0000 |

## 오답 원인별 건수

| 원인 | 건수 |
| --- | --- |
| JOIN_AGGREGATION | 4 |
| METRIC_DEFINITION | 2 |
| SQL_EXECUTION_ERROR | 4 |
| ACTION_MISMATCH | 1 |

## 오답 목록

| id | 유형 | 질문 | 기대 | 실제 | status | error_type | 생성 SQL | 상세 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Q33 | RANKING | 지난주보다 이번 주 판매량이 가장 많이 늘어난 품목 3개는? | sql | sql | OK | JOIN_AGGREGATION | SELECT p.sku, p.name, SUM(sd.qty) AS sales_this_week, SUM(sd2.qty) AS sales_last_week FROM sales_daily sd JOIN sales_daily sd2 ON sd.sku = sd2.sku AND sd.location_id = sd2.location_id AND sd.sale_date BETWEEN DATE '2026-11-02' AND DATE '2026-11-08' AND sd2.sale_date BETWEEN DATE '2026-10-26' AND DATE '2026-11-01' JOIN product p ON p.sku = sd.sku WHERE sd.qty > sd2.qty GROUP BY p.sku, p.name ORDER BY (SUM(sd.qty) - SUM(sd2.qty)) DESC LIMIT 3 | no column matches expected column sku |
| Q35 | METRIC | 최근 13주 동안 예측 합계가 실적 합계보다 큰 품목은 몇 개야? | sql | sql | OK | JOIN_AGGREGATION | SELECT COUNT(*) FROM (SELECT f.sku FROM forecast_weekly f JOIN sales_daily s ON s.sku = f.sku AND s.sale_date BETWEEN DATE '2026-08-10' AND DATE '2026-11-08' GROUP BY f.sku HAVING SUM(f.forecast_qty) > SUM(s.qty)) AS subquery LIMIT 200 | no column matches expected column cnt |
| Q36 | METRIC | 카테고리별 최근 28일 일평균 판매량은? | sql | sql | OK | METRIC_DEFINITION | SELECT category, AVG(qty) AS avg_sales FROM sales_daily JOIN product ON sales_daily.sku = product.sku WHERE sale_date BETWEEN DATE '2026-10-10' AND DATE '2026-11-08' GROUP BY category LIMIT 200 | no column matches expected column avg_daily_qty |
| Q37 | RANKING | 서울센터에서 재고보유일수가 가장 짧은 품목 3개는? | sql | sql | OK | JOIN_AGGREGATION | SELECT p.sku, p.name, (SELECT SUM(on_hand) FROM inventory_daily WHERE sku = p.sku AND snap_date = DATE '2026-11-08')::numeric / NULLIF((SELECT SUM(qty) FROM sales_daily WHERE sku = p.sku AND sale_date BETWEEN DATE '2026-10-12' AND DATE '2026-11-08')::numeric / 28, 0) AS days_of_supply FROM product p JOIN inventory_daily i ON i.sku = p.sku AND i.location_id = (SELECT location_id FROM location WHERE name = '서울센터') AND i.snap_date = DATE '2026-11-08' ORDER BY days_of_supply ASC LIMIT 3 | no column matches expected column sku |
| Q38 | PERIOD_AGG | 10월에 결품이 하루라도 있었던 SKU-거점은 몇 개야? | sql | sql | SQL_EXECUTION_ERROR | SQL_EXECUTION_ERROR | SELECT COUNT(DISTINCT sku, location_id) FROM inventory_daily WHERE snap_date BETWEEN DATE '2026-10-01' AND DATE '2026-10-31' AND on_hand = 0 LIMIT 200 | ERROR: function count(character varying, character varying) does not exist Hint: No function matches the given name and argument types. You might need to add explicit type casts. Position: 8 |
| Q39 | RANKING | 결품률이 가장 높은 거점은 어디야? | sql | sql | OK | METRIC_DEFINITION | SELECT l.name AS location_name, (SELECT COUNT(*) FROM inventory_daily i WHERE i.on_hand = 0 AND i.snap_date = DATE '2026-11-08')::numeric / (SELECT COUNT(*) FROM inventory_daily i WHERE i.snap_date = DATE '2026-11-08') AS scrap_rate FROM location l JOIN inventory_daily i ON l.location_id = i.location_id WHERE i.snap_date = DATE '2026-11-08' ORDER BY scrap_rate DESC LIMIT 1 | no column matches expected column name |
| Q40 | METRIC | 리드타임이 7일인 SKU-거점 중 현재 안전재고에 못 미치는 것은 몇 개야? | sql | sql | SQL_EXECUTION_ERROR | SQL_EXECUTION_ERROR | SELECT COUNT(*) FROM supply_policy WHERE sku = 'SKU-007' AND lead_time_days = 7 AND on_hand < safety_stock LIMIT 200 | ERROR: column "on_hand" does not exist Position: 85 |
| Q42 | SIMPLE_LOOKUP | 현재 입고 예정 수량이 재고보다 많은 SKU-거점은 몇 개야? | sql | sql | OK | JOIN_AGGREGATION | SELECT COUNT(*) FROM inventory_daily WHERE sku = 'SKU-007' AND snap_date = DATE '2026-11-08' AND in_transit > on_hand LIMIT 200 | no column matches expected column cnt |
| Q44 | RANKING | SKU-021의 최근 13주 중 예측과 실적 차이가 가장 컸던 주는 언제야? | sql | sql | SQL_EXECUTION_ERROR | SQL_EXECUTION_ERROR | SELECT fw.week_start, (fw.forecast_qty - (SELECT SUM(sd.qty) FROM sales_daily sd WHERE sd.sku = 'SKU-021' AND sd.sale_date BETWEEN fw.week_start AND DATE '2026-11-08')) AS diff FROM forecast_weekly fw WHERE fw.sku = 'SKU-021' AND fw.week_start BETWEEN DATE '2026-08-10' AND DATE '2026-11-02' ORDER BY ABS(diff) DESC LIMIT 1 | ERROR: column "diff" does not exist Position: 306 |
| Q45 | PERIOD_AGG | 최근 7일 동안 판매가 한 건도 없었던 SKU-거점을 알려줘 | sql | sql | SQL_EXECUTION_ERROR | SQL_EXECUTION_ERROR | SELECT l.name AS location_name, p.sku AS sku FROM location l JOIN sales_daily s ON s.location_id = l.location_id AND s.sku = p.sku JOIN product p ON p.sku = s.sku WHERE p.sku = 'SKU-007' AND s.sale_date BETWEEN DATE '2026-11-02' AND DATE '2026-11-08' AND s.qty = 0 LIMIT 200 | ERROR: missing FROM-clause entry for table "p" Position: 126 |
| Q47 | AMBIGUOUS | 지난번에 본 거 다시 보여줘 | clarify | refuse | OK | ACTION_MISMATCH |  | expected clarify but was refuse |
