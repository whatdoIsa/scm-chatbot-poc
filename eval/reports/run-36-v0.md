# 평가 실행 36 (v0)

| 항목 | 값 |
| --- | --- |
| 모델 | qwen2.5-coder:7b |
| 프롬프트 버전 | v0 |
| temperature / seed | 0.00 / 42 |
| 반복 번호 | 1 |
| 골든셋 sha256 | 8ac43b1c501b6ca7a4c46bdc026554180041a9d36915cca6df7c3fee29a301f3 |
| 정확도 | 5 / 20 = 0.2500 |
| SQL 실행 성공률 | 0.7500 |
| 형식 준수율 | 1.0000 |
| 평균 응답 시간(ms) | 3821 |
| 평균 프롬프트 토큰 | 626.3500 |
| 평균 출력 토큰 | 65.3000 |

## 유형별 정확도

| 유형 | 문항 | 정답 | 정확도 |
| --- | --- | --- | --- |
| PERIOD_AGG | 3 | 0 | 0.0000 |
| METRIC | 4 | 0 | 0.0000 |
| RANKING | 5 | 1 | 0.2000 |
| SIMPLE_LOOKUP | 3 | 0 | 0.0000 |
| AMBIGUOUS | 2 | 2 | 1.0000 |
| REFUSE | 3 | 2 | 0.6667 |

## 오답 원인별 건수

| 원인 | 건수 |
| --- | --- |
| DATE_RANGE | 6 |
| SQL_EXECUTION_ERROR | 4 |
| METRIC_DEFINITION | 3 |
| SCHEMA_MISUNDERSTANDING | 1 |
| ACTION_MISMATCH | 1 |

## 오답 목록

| id | 유형 | 질문 | 기대 | 실제 | status | error_type | 생성 SQL | 상세 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Q31 | PERIOD_AGG | 영남권 거점들의 지난달 판매량 합계는? | sql | sql | OK | DATE_RANGE | SELECT SUM(qty) AS total_sales FROM sales_daily WHERE sale_date BETWEEN (CURRENT_DATE - INTERVAL '1 month') AND (CURRENT_DATE - INTERVAL '1 day') AND location_id IN (SELECT location_id FROM location WHERE region = '영남권') | no column matches expected column qty |
| Q32 | METRIC | A등급 품목 중에서 지금 재주문이 필요한 SKU-거점은 몇 개야? | sql | sql | SQL_EXECUTION_ERROR | SQL_EXECUTION_ERROR | SELECT COUNT(*) FROM product AS p JOIN supply_policy AS sp ON p.sku = sp.sku WHERE p.abc_class = 'A' AND on_hand < reorder_point | ERROR: column "on_hand" does not exist Position: 106 |
| Q33 | RANKING | 지난주보다 이번 주 판매량이 가장 많이 늘어난 품목 3개는? | sql | sql | OK | DATE_RANGE | SELECT p.sku, p.name, SUM(sd.qty) AS total_qty FROM sales_daily sd JOIN product p ON sd.sku = p.sku WHERE sd.sale_date BETWEEN CURRENT_DATE - INTERVAL '2 weeks' AND CURRENT_DATE - INTERVAL '1 week' GROUP BY p.sku, p.name ORDER BY total_qty DESC LIMIT 3 | no column matches expected column sku |
| Q34 | SIMPLE_LOOKUP | 지금 재고가 0인 품목의 이름과 거점 이름을 알려줘 | sql | sql | OK | DATE_RANGE | SELECT p.name, l.name FROM product p JOIN inventory_daily i ON p.sku = i.sku JOIN location l ON i.location_id = l.location_id WHERE i.on_hand = 0 | row count differs: expected 3 but was 94 |
| Q35 | METRIC | 최근 13주 동안 예측 합계가 실적 합계보다 큰 품목은 몇 개야? | sql | sql | SQL_EXECUTION_ERROR | SQL_EXECUTION_ERROR | SELECT COUNT(DISTINCT sku) FROM forecast_weekly AS f JOIN sales_daily AS s ON f.sku = s.sku AND f.location_id = s.location_id WHERE f.week_start BETWEEN CURRENT_DATE - INTERVAL '13 weeks' AND CURRENT_DATE AND f.forecast_qty > SUM(s.qty) GROUP BY f.sku | ERROR: column reference "sku" is ambiguous Position: 23 |
| Q36 | METRIC | 카테고리별 최근 28일 일평균 판매량은? | sql | sql | OK | METRIC_DEFINITION | SELECT p.category, AVG(sd.qty) AS avg_sales FROM sales_daily sd JOIN product p ON sd.sku = p.sku WHERE sd.sale_date >= CURRENT_DATE - INTERVAL '28 days' GROUP BY p.category | no column matches expected column avg_daily_qty |
| Q37 | RANKING | 서울센터에서 재고보유일수가 가장 짧은 품목 3개는? | sql | sql | OK | SCHEMA_MISUNDERSTANDING | SELECT p.sku, p.name, MIN(i.on_hand) AS on_hand FROM product p JOIN inventory_daily i ON p.sku = i.sku AND i.location_id = '서울센터' GROUP BY p.sku, p.name ORDER BY on_hand ASC LIMIT 3 | row count differs: expected 3 but was 0 |
| Q38 | PERIOD_AGG | 10월에 결품이 하루라도 있었던 SKU-거점은 몇 개야? | sql | sql | OK | METRIC_DEFINITION | SELECT COUNT(DISTINCT sku) FROM sales_daily WHERE sale_date BETWEEN '2023-10-01' AND '2023-10-31' AND qty = 0 | no column matches expected column cnt |
| Q39 | RANKING | 결품률이 가장 높은 거점은 어디야? | sql | sql | OK | METRIC_DEFINITION | SELECT l.name AS location_name, AVG(s.qty) AS avg_shortage FROM sales_daily s JOIN location l ON s.location_id = l.location_id WHERE s.qty < 0 GROUP BY l.name ORDER BY avg_shortage DESC LIMIT 1 | row count differs: expected 1 but was 0 |
| Q40 | METRIC | 리드타임이 7일인 SKU-거점 중 현재 안전재고에 못 미치는 것은 몇 개야? | sql | sql | OK | DATE_RANGE | SELECT COUNT(*) FROM supply_policy WHERE lead_time_days = 7 AND safety_stock > (SELECT SUM(on_hand) FROM inventory_daily WHERE sku = supply_policy.sku AND location_id = supply_policy.location_id) | no column matches expected column cnt |
| Q42 | SIMPLE_LOOKUP | 현재 입고 예정 수량이 재고보다 많은 SKU-거점은 몇 개야? | sql | sql | SQL_EXECUTION_ERROR | SQL_EXECUTION_ERROR | SELECT COUNT(*) FROM inventory_daily i JOIN supply_policy s ON i.sku = s.sku AND i.location_id = s.location_id WHERE i.on_hand < s.in_transit | ERROR: column s.in_transit does not exist Hint: Perhaps you meant to reference the column "i.in_transit". Position: 130 |
| Q43 | SIMPLE_LOOKUP | ABC 등급별 현재 재고 합계는? | sql | sql | OK | DATE_RANGE | SELECT p.abc_class, SUM(i.on_hand) AS total_on_hand FROM product p JOIN inventory_daily i ON p.sku = i.sku GROUP BY p.abc_class | no column matches expected column on_hand |
| Q44 | RANKING | SKU-021의 최근 13주 중 예측과 실적 차이가 가장 컸던 주는 언제야? | sql | sql | SQL_EXECUTION_ERROR | SQL_EXECUTION_ERROR | SELECT week_start, ABS(forecast_qty - SUM(qty)) AS diff FROM forecast_weekly fw JOIN sales_daily sd ON fw.sku = sd.sku AND fw.location_id = sd.location_id WHERE fw.sku = 'SKU-021' AND sd.sale_date BETWEEN CURRENT_DATE - INTERVAL '13 weeks' AND CURRENT_DATE GROUP BY week_start ORDER BY diff DESC LIMIT 1 | ERROR: column "fw.forecast_qty" must appear in the GROUP BY clause or be used in an aggregate function Position: 24 |
| Q45 | PERIOD_AGG | 최근 7일 동안 판매가 한 건도 없었던 SKU-거점을 알려줘 | sql | sql | OK | DATE_RANGE | SELECT sku, location_id FROM sales_daily WHERE sale_date BETWEEN CURRENT_DATE - INTERVAL '7 days' AND CURRENT_DATE AND qty = 0 GROUP BY sku, location_id HAVING COUNT(*) = 0 | row count differs: expected 2 but was 0 |
| Q49 | REFUSE | 내년 1월 판매량을 알려줘 | refuse | sql | OK | ACTION_MISMATCH | SELECT sku, SUM(qty) AS total_sales FROM sales_daily WHERE sale_date BETWEEN '2024-01-01' AND '2024-01-31' GROUP BY sku | expected refuse but was sql |
