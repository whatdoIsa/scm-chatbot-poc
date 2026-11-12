# 평가 실행 32 (v2)

| 항목 | 값 |
| --- | --- |
| 모델 | qwen2.5-coder:7b |
| 프롬프트 버전 | v2 |
| temperature / seed | 0.00 / 42 |
| 반복 번호 | 1 |
| 골든셋 sha256 | e8c276750be331808a7e6c0cd86381ed9122d348b6494de8b809e05797b28576 |
| 정확도 | 19 / 30 = 0.6333 |
| SQL 실행 성공률 | 1.0000 |
| 형식 준수율 | 1.0000 |
| 평균 응답 시간(ms) | 8915 |
| 평균 프롬프트 토큰 | 1496.0000 |
| 평균 출력 토큰 | 74.2000 |

## 유형별 정확도

| 유형 | 문항 | 정답 | 정확도 |
| --- | --- | --- | --- |
| SIMPLE_LOOKUP | 6 | 4 | 0.6667 |
| PERIOD_AGG | 8 | 7 | 0.8750 |
| METRIC | 6 | 3 | 0.5000 |
| RANKING | 4 | 2 | 0.5000 |
| AMBIGUOUS | 2 | 1 | 0.5000 |
| REFUSE | 4 | 2 | 0.5000 |

## 오답 원인별 건수

| 원인 | 건수 |
| --- | --- |
| JOIN_AGGREGATION | 3 |
| DATE_RANGE | 1 |
| METRIC_DEFINITION | 3 |
| ACTION_MISMATCH | 4 |

## 오답 목록

| id | 유형 | 질문 | 기대 | 실제 | status | error_type | 생성 SQL | 상세 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Q01 | SIMPLE_LOOKUP | SKU-007의 현재 재고는? | sql | sql | OK | JOIN_AGGREGATION | SELECT on_hand FROM inventory_daily WHERE sku = 'SKU-007' AND snap_date = DATE '2026-11-08' LIMIT 200 | row count differs: expected 1 but was 5 |
| Q05 | SIMPLE_LOOKUP | 지금 재고가 하나도 없는 SKU-거점을 모두 알려줘 | sql | sql | OK | JOIN_AGGREGATION | SELECT l.name AS location_name FROM location l JOIN inventory_daily i ON i.location_id = l.location_id WHERE i.snap_date = DATE '2026-11-08' AND i.on_hand = 0 AND i.in_transit = 0 GROUP BY l.name LIMIT 200 | no column matches expected column sku |
| Q13 | PERIOD_AGG | 최근 30일 카테고리별 판매량을 보여줘 | sql | sql | OK | DATE_RANGE | SELECT p.category, SUM(sd.qty) AS total_sales FROM sales_daily sd JOIN product p ON p.sku = sd.sku WHERE sd.sale_date BETWEEN DATE '2026-10-09' AND DATE '2026-11-08' GROUP BY p.category ORDER BY total_sales DESC LIMIT 200 | no column matches expected column qty |
| Q16 | METRIC | SKU-036의 서울센터 결품률은? | sql | sql | OK | METRIC_DEFINITION | SELECT CAST(SUM(CASE WHEN on_hand = 0 THEN 1 ELSE 0 END) AS numeric) * 100 / COUNT(*) AS out_of_stock_rate FROM inventory_daily i JOIN location l ON l.location_id = i.location_id WHERE i.sku = 'SKU-036' AND l.name = '서울센터' AND i.snap_date = DATE '2026-11-08' LIMIT 200 | no column matches expected column stockout_rate |
| Q19 | METRIC | SKU-008의 최근 13주 예측 정확도는? | sql | refuse | OK | ACTION_MISMATCH |  | expected sql but was refuse |
| Q20 | METRIC | 최근 28일 SKU-029의 일평균 판매량은? | sql | sql | OK | METRIC_DEFINITION | SELECT AVG(qty) AS avg_qty FROM sales_daily WHERE sku = 'SKU-029' AND sale_date BETWEEN DATE '2026-10-10' AND DATE '2026-11-08' LIMIT 200 | no column matches expected column avg_daily_qty |
| Q21 | RANKING | 최근 13주 예측 정확도가 가장 낮은 SKU 4개를 낮은 순서대로 알려줘 | sql | sql | OK | METRIC_DEFINITION | SELECT f.sku, 1 - (SUM(ABS(s.qty - f.forecast_qty))::numeric / SUM(s.qty)) AS wmape FROM sales_daily s JOIN forecast_weekly f ON f.sku = s.sku AND f.week_start = DATE '2026-08-10' WHERE s.sale_date BETWEEN DATE '2026-08-10' AND DATE '2026-11-08' GROUP BY f.sku ORDER BY wmape ASC LIMIT 4 | no column matches expected column sku |
| Q24 | RANKING | 재고보유일수가 가장 짧은 품목 5개는? | sql | sql | OK | JOIN_AGGREGATION | SELECT sku, (SELECT SUM(on_hand) FROM inventory_daily WHERE sku = p.sku AND snap_date = DATE '2026-11-08')::numeric / NULLIF((SELECT SUM(qty) FROM sales_daily WHERE sku = p.sku AND sale_date BETWEEN DATE '2026-10-12' AND DATE '2026-11-08')::numeric / 28, 0) AS days_of_supply FROM product p WHERE p.abc_class = 'A' ORDER BY days_of_supply ASC LIMIT 5 | no column matches expected column sku |
| Q25 | AMBIGUOUS | 재고 어때? | clarify | sql | OK | ACTION_MISMATCH | SELECT sku, SUM(on_hand) AS total_on_hand FROM inventory_daily WHERE snap_date = DATE '2026-11-08' GROUP BY sku ORDER BY total_on_hand DESC LIMIT 200 | expected clarify but was sql |
| Q29 | REFUSE | 올해 3월 판매량은? | refuse | sql | OK | ACTION_MISMATCH | SELECT SUM(qty) AS total_sales FROM sales_daily WHERE sale_date BETWEEN DATE '2023-03-01' AND DATE '2023-03-31' LIMIT 200 | expected refuse but was sql |
| Q30 | REFUSE | SKU-001의 지난달 영업이익은? | refuse | sql | OK | ACTION_MISMATCH | SELECT SUM((s.qty * p.unit_cost) - (s.qty * p.unit_cost * 0.1)) AS profit FROM sales_daily s JOIN product p ON p.sku = s.sku WHERE s.sku = 'SKU-001' AND s.sale_date BETWEEN DATE '2026-10-01' AND DATE '2026-10-31' LIMIT 200 | expected refuse but was sql |
