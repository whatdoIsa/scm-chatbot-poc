# 평가 실행 22 (v1)

| 항목 | 값 |
| --- | --- |
| 모델 | qwen2.5-coder:7b |
| 프롬프트 버전 | v1 |
| temperature / seed | 0.0 / 42 |
| 반복 번호 | 1 |
| 골든셋 sha256 | e8c276750be331808a7e6c0cd86381ed9122d348b6494de8b809e05797b28576 |
| 정확도 | 22 / 30 = 0.7333 |
| SQL 실행 성공률 | 0.9600 |
| 형식 준수율 | 1.0000 |
| 평균 응답 시간(ms) | 4494 |
| 평균 프롬프트 토큰 | 2025.3667 |
| 평균 출력 토큰 | 69.4667 |

## 유형별 정확도

| 유형 | 문항 | 정답 | 정확도 |
| --- | --- | --- | --- |
| SIMPLE_LOOKUP | 6 | 5 | 0.8333 |
| PERIOD_AGG | 8 | 7 | 0.8750 |
| METRIC | 6 | 2 | 0.3333 |
| RANKING | 4 | 3 | 0.7500 |
| AMBIGUOUS | 2 | 2 | 1.0000 |
| REFUSE | 4 | 3 | 0.7500 |

## 오답 원인별 건수

| 원인 | 건수 |
| --- | --- |
| UNCLASSIFIED | 6 |
| SQL_EXECUTION_ERROR | 1 |
| ACTION_MISMATCH | 1 |

## 오답 목록

| id | 유형 | 질문 | 기대 | 실제 | status | error_type | 생성 SQL | 상세 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Q01 | SIMPLE_LOOKUP | SKU-007의 현재 재고는? | sql | sql | OK | UNCLASSIFIED | SELECT on_hand FROM inventory_daily WHERE sku = 'SKU-007' AND snap_date = DATE '2026-11-08' | row count differs: expected 1 but was 5 |
| Q13 | PERIOD_AGG | 최근 30일 카테고리별 판매량을 보여줘 | sql | sql | OK | UNCLASSIFIED | SELECT p.category, SUM(s.qty) AS total_sales FROM sales_daily s JOIN product p ON p.sku = s.sku WHERE s.sale_date BETWEEN DATE '2026-10-08' AND DATE '2026-11-08' GROUP BY p.category ORDER BY total_sales DESC | no column matches expected column qty |
| Q15 | METRIC | 서울센터 SKU-026의 재고보유일수는? | sql | sql | OK | UNCLASSIFIED | SELECT (SELECT SUM(on_hand) FROM inventory_daily WHERE sku = 'SKU-026' AND snap_date = DATE '2026-11-08')::numeric / NULLIF((SELECT SUM(qty) FROM sales_daily WHERE sku = 'SKU-026' AND sale_date BETWEEN DATE '2026-10-12' AND DATE '2026-11-08')::numeric / 28, 0) AS days_of_supply | no column matches expected column days_of_supply |
| Q16 | METRIC | SKU-036의 서울센터 결품률은? | sql | sql | OK | UNCLASSIFIED | SELECT (SELECT COUNT(*) FROM inventory_daily WHERE sku = 'SKU-036' AND snap_date = DATE '2026-11-08' AND on_hand = 0)::numeric / NULLIF((SELECT COUNT(*) FROM inventory_daily WHERE sku = 'SKU-036' AND snap_date = DATE '2026-11-08'), 0) AS out_of_stock_rate | no column matches expected column stockout_rate |
| Q19 | METRIC | SKU-008의 최근 13주 예측 정확도는? | sql | sql | SQL_EXECUTION_ERROR | SQL_EXECUTION_ERROR | WITH actual_sales AS ( SELECT week_start, SUM(qty) AS actual_qty FROM sales_daily WHERE sku = 'SKU-008' AND sale_date BETWEEN DATE '2026-08-10' AND DATE '2026-11-02' GROUP BY week_start ), forecast_data AS ( SELECT week_start, forecast_qty FROM forecast_weekly WHERE sku = 'SKU-008' AND week_start BETWEEN DATE '2026-08-10' AND DATE '2026-11-02' ) SELECT 1 - SUM(ABS(actual_qty - forecast_qty)) / SUM(actual_qty) AS wmape FROM actual_sales JOIN forecast_data ON actual_sales.week_start = forecast_data.week_start | ERROR: column "week_start" does not exist Position: 31 |
| Q20 | METRIC | 최근 28일 SKU-029의 일평균 판매량은? | sql | sql | OK | UNCLASSIFIED | SELECT AVG(qty) AS avg_qty FROM sales_daily WHERE sku = 'SKU-029' AND sale_date BETWEEN DATE '2026-10-12' AND DATE '2026-11-08' | no column matches expected column avg_daily_qty |
| Q21 | RANKING | 최근 13주 예측 정확도가 가장 낮은 SKU 4개를 낮은 순서대로 알려줘 | sql | sql | OK | UNCLASSIFIED | SELECT f.sku, 1 - (SELECT SUM(ABS(s.qty - f.forecast_qty))::numeric / SUM(s.qty) FROM sales_daily s WHERE s.sku = f.sku AND s.sale_date BETWEEN DATE '2026-08-10' AND DATE '2026-11-08') AS wmape FROM forecast_weekly f WHERE f.week_start BETWEEN DATE '2026-08-10' AND DATE '2026-11-02' ORDER BY wmape ASC LIMIT 4 | no column matches expected column sku |
| Q29 | REFUSE | 올해 3월 판매량은? | refuse | sql | OK | ACTION_MISMATCH | SELECT SUM(qty) AS total_sales FROM sales_daily WHERE sale_date BETWEEN DATE '2023-03-01' AND DATE '2023-03-31' | expected refuse but was sql |
