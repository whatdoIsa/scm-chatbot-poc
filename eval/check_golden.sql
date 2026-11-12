-- 실제 DB에서 골든셋 초안을 확정하기 전에 실행한다.
-- docker compose exec -T -e PGPASSWORD=$SCM_RO_PASSWORD db psql -h 127.0.0.1 -U scm_ro -d scm -f - < eval/check_golden.sql

\echo '== Q05 현재 결품 (기대 3행: SKU-002, SKU-019, SKU-040)'
SELECT sku, location_id FROM scm.inventory_daily
WHERE snap_date = DATE '2026-11-08' AND on_hand = 0 ORDER BY sku;

\echo '== Q09 vs Q10 (이번 주와 지난주 값이 달라야 함)'
SELECT
  SUM(qty) FILTER (WHERE sale_date BETWEEN DATE '2026-11-02' AND DATE '2026-11-08') AS this_week,
  SUM(qty) FILTER (WHERE sale_date BETWEEN DATE '2026-10-26' AND DATE '2026-11-01') AS last_week,
  SUM(qty) FILTER (WHERE sale_date BETWEEN DATE '2026-11-01' AND DATE '2026-11-07') AS off_by_one
FROM scm.sales_daily WHERE sku = 'SKU-042';

\echo '== Q16 결품률 (기대 16/91 = 0.17582)'
SELECT AVG(CASE WHEN on_hand = 0 THEN 1.0 ELSE 0.0 END)
FROM scm.inventory_daily WHERE sku = 'SKU-036' AND location_id = 'LOC-01';

\echo '== Q17, Q18 (기대 17, 29 / < 로 쓰면 26)'
SELECT
  COUNT(*) FILTER (WHERE i.on_hand < p.safety_stock) AS below_ss,
  COUNT(*) FILTER (WHERE i.on_hand + i.in_transit <= p.reorder_point) AS reorder_le,
  COUNT(*) FILTER (WHERE i.on_hand + i.in_transit < p.reorder_point) AS reorder_lt
FROM scm.inventory_daily i
JOIN scm.supply_policy p USING (sku, location_id)
WHERE i.snap_date = DATE '2026-11-08';

\echo '== Q22 최근 7일 판매 상위 6 (3위와 4위 차이가 0.1%보다 커야 함)'
SELECT sku, SUM(qty) AS qty,
  ROUND(100.0 * (SUM(qty) - LEAD(SUM(qty)) OVER (ORDER BY SUM(qty) DESC, sku)) / SUM(qty), 3) AS gap_pct_to_next
FROM scm.sales_daily
WHERE sale_date BETWEEN DATE '2026-11-02' AND DATE '2026-11-08'
GROUP BY sku ORDER BY qty DESC, sku LIMIT 6;

\echo '== Q23 지난달 거점 순위 (인접 차이가 0.1%보다 커야 함)'
SELECT location_id, SUM(qty) AS qty,
  ROUND(100.0 * (SUM(qty) - LEAD(SUM(qty)) OVER (ORDER BY SUM(qty) DESC, location_id)) / SUM(qty), 3) AS gap_pct_to_next
FROM scm.sales_daily
WHERE sale_date BETWEEN DATE '2026-10-01' AND DATE '2026-10-31'
GROUP BY location_id ORDER BY qty DESC, location_id;

\echo '== Q24 SKU 단위 재고보유일수 하위 7 (5위와 6위 차이가 0.1%보다 커야 함)'
WITH oh AS (
  SELECT sku, SUM(on_hand)::numeric AS on_hand FROM scm.inventory_daily
  WHERE snap_date = DATE '2026-11-08' GROUP BY sku),
d AS (
  SELECT sku, SUM(qty)::numeric / 28 AS daily FROM scm.sales_daily
  WHERE sale_date BETWEEN DATE '2026-10-12' AND DATE '2026-11-08' GROUP BY sku),
r AS (SELECT oh.sku, oh.on_hand / d.daily AS dos FROM oh JOIN d USING (sku) WHERE d.daily > 0)
SELECT sku, ROUND(dos, 3) AS dos,
  ROUND(100.0 * (LEAD(dos) OVER (ORDER BY dos, sku) - dos) / NULLIF(dos, 0), 3) AS gap_pct_to_next
FROM r ORDER BY dos, sku LIMIT 7;

\echo '== Q24 비교용: SKU-거점 단위로 풀면 결과가 달라지는지 (달라야 변별력 있음)'
WITH d AS (
  SELECT sku, location_id, SUM(qty)::numeric / 28 AS daily FROM scm.sales_daily
  WHERE sale_date BETWEEN DATE '2026-10-12' AND DATE '2026-11-08' GROUP BY sku, location_id)
SELECT i.sku, i.location_id, ROUND(i.on_hand / d.daily, 3) AS dos
FROM scm.inventory_daily i JOIN d USING (sku, location_id)
WHERE i.snap_date = DATE '2026-11-08' AND d.daily > 0
ORDER BY dos, i.sku LIMIT 5;

\echo '== 정답 결과 행 수 상한 (모든 sql 문항 200행 이하는 GoldenSetIT에서 확인)'
