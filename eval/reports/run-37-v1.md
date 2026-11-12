# 평가 실행 37 (v1)

| 항목 | 값 |
| --- | --- |
| 모델 | qwen2.5-coder:7b |
| 프롬프트 버전 | v1 |
| temperature / seed | 0.00 / 42 |
| 반복 번호 | 1 |
| 골든셋 sha256 | 8ac43b1c501b6ca7a4c46bdc026554180041a9d36915cca6df7c3fee29a301f3 |
| 정확도 | 11 / 20 = 0.5500 |
| SQL 실행 성공률 | 0.7333 |
| 형식 준수율 | 1.0000 |
| 평균 응답 시간(ms) | 4943 |
| 평균 프롬프트 토큰 | 2030.3500 |
| 평균 출력 토큰 | 80.7000 |

## 유형별 정확도

| 유형 | 문항 | 정답 | 정확도 |
| --- | --- | --- | --- |
| PERIOD_AGG | 3 | 1 | 0.3333 |
| METRIC | 4 | 2 | 0.5000 |
| RANKING | 5 | 1 | 0.2000 |
| SIMPLE_LOOKUP | 3 | 3 | 1.0000 |
| AMBIGUOUS | 2 | 1 | 0.5000 |
| REFUSE | 3 | 3 | 1.0000 |

## 오답 원인별 건수

| 원인 | 건수 |
| --- | --- |
| SQL_EXECUTION_ERROR | 4 |
| METRIC_DEFINITION | 2 |
| JOIN_AGGREGATION | 2 |
| ACTION_MISMATCH | 1 |

## 오답 목록

| id | 유형 | 질문 | 기대 | 실제 | status | error_type | 생성 SQL | 상세 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Q33 | RANKING | 지난주보다 이번 주 판매량이 가장 많이 늘어난 품목 3개는? | sql | sql | SQL_EXECUTION_ERROR | SQL_EXECUTION_ERROR | SELECT p.sku, p.name, SUM(s1.qty) AS sales_last_week, SUM(s2.qty) AS sales_this_week FROM sales_daily s1 JOIN sales_daily s2 ON s1.sku = s2.sku AND s1.location_id = s2.location_id AND s1.sale_date = s2.sale_date - INTERVAL '1 week' JOIN product p ON p.sku = s1.sku WHERE s1.sale_date BETWEEN DATE '2026-10-26' AND DATE '2026-11-01' AND s2.sale_date BETWEEN DATE '2026-11-02' AND DATE '2026-11-08' GROUP BY p.sku, p.name ORDER BY sales_this_week - sales_last_week DESC LIMIT 3 | ERROR: column "sales_this_week" does not exist Position: 430 |
| Q35 | METRIC | 최근 13주 동안 예측 합계가 실적 합계보다 큰 품목은 몇 개야? | sql | sql | SQL_EXECUTION_ERROR | SQL_EXECUTION_ERROR | SELECT COUNT(DISTINCT sku) FROM forecast_weekly fw JOIN sales_daily sd ON sd.sku = fw.sku AND sd.sale_date BETWEEN DATE '2026-08-10' AND DATE '2026-11-02' WHERE fw.week_start BETWEEN DATE '2026-08-10' AND DATE '2026-11-02' AND SUM(sd.qty) < SUM(fw.forecast_qty) | ERROR: column reference "sku" is ambiguous Position: 23 |
| Q36 | METRIC | 카테고리별 최근 28일 일평균 판매량은? | sql | sql | OK | METRIC_DEFINITION | SELECT p.category, AVG(s.qty) AS avg_sales FROM sales_daily s JOIN product p ON p.sku = s.sku WHERE s.sale_date BETWEEN DATE '2026-10-12' AND DATE '2026-11-08' GROUP BY p.category | no column matches expected column avg_daily_qty |
| Q37 | RANKING | 서울센터에서 재고보유일수가 가장 짧은 품목 3개는? | sql | sql | OK | JOIN_AGGREGATION | SELECT p.sku, p.name, (SELECT SUM(on_hand) FROM inventory_daily WHERE sku = p.sku AND snap_date = DATE '2026-11-08')::numeric / NULLIF((SELECT SUM(qty) FROM sales_daily WHERE sku = p.sku AND sale_date BETWEEN DATE '2026-10-12' AND DATE '2026-11-08')::numeric / 28, 0) AS days_of_supply FROM product p JOIN inventory_daily i ON i.sku = p.sku AND i.location_id = (SELECT location_id FROM location WHERE name = '서울센터') AND i.snap_date = DATE '2026-11-08' ORDER BY days_of_supply ASC LIMIT 3 | no column matches expected column sku |
| Q38 | PERIOD_AGG | 10월에 결품이 하루라도 있었던 SKU-거점은 몇 개야? | sql | sql | SQL_EXECUTION_ERROR | SQL_EXECUTION_ERROR | SELECT COUNT(DISTINCT sku, location_id) FROM inventory_daily WHERE snap_date BETWEEN DATE '2026-10-01' AND DATE '2026-10-31' AND on_hand = 0 | ERROR: function count(character varying, character varying) does not exist Hint: No function matches the given name and argument types. You might need to add explicit type casts. Position: 8 |
| Q39 | RANKING | 결품률이 가장 높은 거점은 어디야? | sql | sql | OK | METRIC_DEFINITION | SELECT l.name AS location_name, COUNT(*) AS zero_stock_count FROM inventory_daily i JOIN location l ON l.location_id = i.location_id WHERE i.snap_date = DATE '2026-11-08' AND i.on_hand = 0 GROUP BY l.name ORDER BY zero_stock_count DESC LIMIT 1 | no column matches expected column name |
| Q44 | RANKING | SKU-021의 최근 13주 중 예측과 실적 차이가 가장 컸던 주는 언제야? | sql | sql | SQL_EXECUTION_ERROR | SQL_EXECUTION_ERROR | SELECT fw.week_start, ABS(SUM(sd.qty) - fw.forecast_qty) AS diff FROM sales_daily sd JOIN forecast_weekly fw ON sd.sku = fw.sku AND sd.location_id = fw.location_id AND sd.sale_date BETWEEN DATE '2026-08-10' AND DATE '2026-11-02' AND fw.week_start BETWEEN DATE '2026-08-10' AND DATE '2026-11-02' WHERE sd.sku = 'SKU-021' GROUP BY fw.week_start ORDER BY diff DESC LIMIT 1 | ERROR: column "fw.forecast_qty" must appear in the GROUP BY clause or be used in an aggregate function Position: 41 |
| Q45 | PERIOD_AGG | 최근 7일 동안 판매가 한 건도 없었던 SKU-거점을 알려줘 | sql | sql | OK | JOIN_AGGREGATION | SELECT p.sku, l.name FROM product p JOIN sales_daily s ON s.sku = p.sku JOIN location l ON l.location_id = s.location_id WHERE s.sale_date BETWEEN DATE '2026-11-02' AND DATE '2026-11-08' AND s.qty = 0 GROUP BY p.sku, l.name | row count differs: expected 2 but was 4 |
| Q47 | AMBIGUOUS | 지난번에 본 거 다시 보여줘 | clarify | refuse | OK | ACTION_MISMATCH |  | expected clarify but was refuse |
