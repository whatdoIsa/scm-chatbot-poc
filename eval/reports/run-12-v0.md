# 평가 실행 12 (v0)

| 항목 | 값 |
| --- | --- |
| 모델 | qwen2.5-coder:7b |
| 프롬프트 버전 | v0 |
| temperature / seed | 0.0 / 42 |
| 반복 번호 | 1 |
| 골든셋 sha256 | e8c276750be331808a7e6c0cd86381ed9122d348b6494de8b809e05797b28576 |
| 정확도 | 7 / 30 = 0.2333 |
| SQL 실행 성공률 | 0.9259 |
| 형식 준수율 | 1.0000 |
| 평균 응답 시간(ms) | 3579 |
| 평균 프롬프트 토큰 | 621.3667 |
| 평균 출력 토큰 | 61.9333 |

## 유형별 정확도

| 유형 | 문항 | 정답 | 정확도 |
| --- | --- | --- | --- |
| SIMPLE_LOOKUP | 6 | 2 | 0.3333 |
| PERIOD_AGG | 8 | 0 | 0.0000 |
| METRIC | 6 | 0 | 0.0000 |
| RANKING | 4 | 2 | 0.5000 |
| AMBIGUOUS | 2 | 1 | 0.5000 |
| REFUSE | 4 | 2 | 0.5000 |

## 오답 원인별 건수

| 원인 | 건수 |
| --- | --- |
| UNCLASSIFIED | 18 |
| SQL_EXECUTION_ERROR | 2 |
| ACTION_MISMATCH | 3 |

## 오답 목록

| id | 유형 | 질문 | 기대 | 실제 | status | error_type | 생성 SQL | 상세 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Q01 | SIMPLE_LOOKUP | SKU-007의 현재 재고는? | sql | sql | OK | UNCLASSIFIED | SELECT on_hand FROM inventory_daily WHERE sku = 'SKU-007' AND snap_date = CURRENT_DATE | row count differs: expected 1 but was 0 |
| Q02 | SIMPLE_LOOKUP | 부산센터의 SKU-016 입고 예정 수량은? | sql | sql | OK | UNCLASSIFIED | SELECT in_transit FROM inventory_daily WHERE sku = 'SKU-016' AND location_id = (SELECT location_id FROM location WHERE name = '부산센터') | row count differs: expected 1 but was 91 |
| Q04 | SIMPLE_LOOKUP | SKU-7의 카테고리와 ABC 등급은? | sql | sql | OK | UNCLASSIFIED | SELECT category, abc_class FROM product WHERE sku = 'SKU-7' | row count differs: expected 1 but was 0 |
| Q05 | SIMPLE_LOOKUP | 지금 재고가 하나도 없는 SKU-거점을 모두 알려줘 | sql | sql | OK | UNCLASSIFIED | SELECT sku, location_id FROM inventory_daily WHERE on_hand = 0 AND in_transit = 0 | row count differs: expected 3 but was 78 |
| Q07 | PERIOD_AGG | 어제 서울센터 전체 판매량은? | sql | sql | OK | UNCLASSIFIED | SELECT SUM(qty) AS total_sales FROM sales_daily WHERE sale_date = CURRENT_DATE - INTERVAL '1 day' AND location_id = (SELECT location_id FROM location WHERE name = '서울센터') | no column matches expected column qty |
| Q08 | PERIOD_AGG | 최근 7일 동안 SKU-005의 판매량 합계는? | sql | sql | OK | UNCLASSIFIED | SELECT SUM(qty) AS total_sales FROM sales_daily WHERE sku = 'SKU-005' AND sale_date >= CURRENT_DATE - INTERVAL '7 days' | no column matches expected column qty |
| Q09 | PERIOD_AGG | 이번 주 SKU-042 출고량은? | sql | sql | OK | UNCLASSIFIED | SELECT SUM(qty) AS total_qty FROM sales_daily WHERE sku = 'SKU-042' AND sale_date BETWEEN CURRENT_DATE - INTERVAL '6 days' AND CURRENT_DATE | no column matches expected column qty |
| Q10 | PERIOD_AGG | 지난주 SKU-042 출고량은? | sql | sql | OK | UNCLASSIFIED | SELECT SUM(qty) AS total_qty FROM sales_daily WHERE sku = 'SKU-042' AND sale_date BETWEEN CURRENT_DATE - INTERVAL '7 days' AND CURRENT_DATE - INTERVAL '1 day' | no column matches expected column qty |
| Q11 | PERIOD_AGG | 지난달 대구센터 판매량 합계는? | sql | sql | OK | UNCLASSIFIED | SELECT SUM(qty) AS total_sales FROM sales_daily WHERE location_id = 'DGS' AND sale_date BETWEEN date_trunc('month', current_date - interval '1 month') AND date_trunc('month', current_date) - interval '1 day' | no column matches expected column qty |
| Q12 | PERIOD_AGG | 이번 달 부산센터 SKU-009 판매량은? | sql | sql | OK | UNCLASSIFIED | SELECT SUM(qty) AS total_sales FROM sales_daily WHERE location_id = '부산센터' AND sku = 'SKU-009' AND sale_date BETWEEN date_trunc('month', current_date) AND date_trunc('month', current_date) + interval '1 month' - interval '1 day' | no column matches expected column qty |
| Q13 | PERIOD_AGG | 최근 30일 카테고리별 판매량을 보여줘 | sql | sql | OK | UNCLASSIFIED | SELECT category, SUM(qty) AS total_sales FROM sales_daily JOIN product ON sales_daily.sku = product.sku WHERE sale_date >= CURRENT_DATE - INTERVAL '30 days' GROUP BY category | no column matches expected column qty |
| Q14 | PERIOD_AGG | 9월 한 달 동안 거점별 판매량 합계는? | sql | sql | OK | UNCLASSIFIED | SELECT location_id, SUM(qty) AS total_sales FROM sales_daily WHERE sale_date BETWEEN '2023-09-01' AND '2023-09-30' GROUP BY location_id | row count differs: expected 5 but was 0 |
| Q15 | METRIC | 서울센터 SKU-026의 재고보유일수는? | sql | sql | OK | UNCLASSIFIED | SELECT safety_stock FROM supply_policy WHERE sku = 'SKU-026' AND location_id = (SELECT location_id FROM location WHERE name = '서울센터') | no column matches expected column days_of_supply |
| Q16 | METRIC | SKU-036의 서울센터 결품률은? | sql | sql | OK | UNCLASSIFIED | SELECT SUM(CASE WHEN qty = 0 THEN 1 ELSE 0 END) * 100.0 / COUNT(*) AS defect_rate FROM sales_daily WHERE sku = 'SKU-036' AND location_id = 'LOC-SEOUL' AND sale_date BETWEEN '2023-01-01' AND '2023-12-31' | no column matches expected column stockout_rate |
| Q17 | METRIC | 현재 안전재고에 못 미치는 SKU-거점은 몇 개야? | sql | sql | OK | UNCLASSIFIED | SELECT COUNT(*) FROM product AS p JOIN supply_policy AS sp ON p.sku = sp.sku WHERE p.abc_class = 'A' AND sp.safety_stock > (SELECT SUM(on_hand) FROM inventory_daily WHERE sku = p.sku GROUP BY sku) | no column matches expected column cnt |
| Q18 | METRIC | 지금 재주문이 필요한 SKU-거점 수는? | sql | sql | SQL_EXECUTION_ERROR | SQL_EXECUTION_ERROR | SELECT COUNT(*) FROM supply_policy WHERE sku IN (SELECT sku FROM product WHERE abc_class = 'A') AND on_hand < reorder_point | ERROR: column "on_hand" does not exist Position: 101 |
| Q19 | METRIC | SKU-008의 최근 13주 예측 정확도는? | sql | sql | SQL_EXECUTION_ERROR | SQL_EXECUTION_ERROR | SELECT AVG(forecast_qty - SUM(qty)) AS accuracy FROM forecast_weekly f JOIN sales_daily s ON f.sku = s.sku AND f.location_id = s.location_id WHERE f.sku = 'SKU-008' AND f.week_start BETWEEN CURRENT_DATE - INTERVAL '13 weeks' AND CURRENT_DATE - INTERVAL '1 week' | ERROR: aggregate function calls cannot be nested Position: 27 |
| Q20 | METRIC | 최근 28일 SKU-029의 일평균 판매량은? | sql | sql | OK | UNCLASSIFIED | SELECT AVG(qty) AS avg_sales FROM sales_daily WHERE sku = 'SKU-029' AND sale_date >= CURRENT_DATE - INTERVAL '28 days' | no column matches expected column avg_daily_qty |
| Q21 | RANKING | 최근 13주 예측 정확도가 가장 낮은 SKU 4개를 낮은 순서대로 알려줘 | sql | sql | OK | UNCLASSIFIED | SELECT sku FROM forecast_weekly WHERE week_start BETWEEN CURRENT_DATE - INTERVAL '13 weeks' AND CURRENT_DATE ORDER BY forecast_qty ASC LIMIT 4 | no column matches expected column sku |
| Q24 | RANKING | 재고보유일수가 가장 짧은 품목 5개는? | sql | sql | OK | UNCLASSIFIED | SELECT p.sku, p.name, sp.reorder_point - inv.on_hand AS days_until_reorder FROM product p JOIN supply_policy sp ON p.sku = sp.sku JOIN inventory_daily inv ON p.sku = inv.sku AND inv.snap_date = (SELECT MAX(snap_date) FROM inventory_daily WHERE sku = p.sku) ORDER BY days_until_reorder ASC LIMIT 5 | no column matches expected column sku |
| Q25 | AMBIGUOUS | 재고 어때? | clarify | sql | OK | ACTION_MISMATCH | SELECT location_id, sku, on_hand, in_transit FROM inventory_daily WHERE snap_date = CURRENT_DATE | expected clarify but was sql |
| Q29 | REFUSE | 올해 3월 판매량은? | refuse | sql | OK | ACTION_MISMATCH | SELECT SUM(qty) AS total_sales FROM sales_daily WHERE sale_date BETWEEN '2023-03-01' AND '2023-03-31' | expected refuse but was sql |
| Q30 | REFUSE | SKU-001의 지난달 영업이익은? | refuse | sql | OK | ACTION_MISMATCH | SELECT SUM((p.unit_cost * s.qty) - (p.unit_cost * s.qty * 0.1)) AS profit FROM product p JOIN sales_daily s ON p.sku = s.sku WHERE s.sku = 'SKU-001' AND s.sale_date BETWEEN (CURRENT_DATE - INTERVAL '1 month') AND (CURRENT_DATE - INTERVAL '1 day') | expected refuse but was sql |
