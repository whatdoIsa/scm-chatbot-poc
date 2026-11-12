-- 시드와 읽기 전용 역할 권한 검증. psql -At로 실행하면 한 줄에 name|ok(t 또는 f)가 나온다.
-- 지표 정의는 PRD 5.3, 하한은 PRD 5.5를 따른다. 기준일은 2026-11-08.

WITH
sku_daily AS (
  SELECT sku, sale_date, sum(qty) AS qty
  FROM scm.sales_daily
  GROUP BY sku, sale_date
),
promo_sku AS (
  SELECT sku
  FROM sku_daily
  GROUP BY sku
  HAVING max(qty) >= 3 * avg(qty)
),
stockout_pair AS (
  SELECT DISTINCT sku, location_id
  FROM scm.inventory_daily
  WHERE on_hand = 0
),
as_of AS (
  SELECT i.sku, i.location_id, i.on_hand, i.in_transit, p.safety_stock, p.reorder_point
  FROM scm.inventory_daily i
  JOIN scm.supply_policy p USING (sku, location_id)
  WHERE i.snap_date = DATE '2026-11-08'
),
weekly AS (
  SELECT f.sku, f.location_id, f.week_start, f.forecast_qty,
         (SELECT coalesce(sum(s.qty), 0)
          FROM scm.sales_daily s
          WHERE s.sku = f.sku
            AND s.location_id = f.location_id
            AND s.sale_date BETWEEN f.week_start AND f.week_start + 6) AS actual_qty
  FROM scm.forecast_weekly f
),
-- WMAPE를 (주, SKU, 거점) 행 단위 오차로 계산한 값
wmape_by_row AS (
  SELECT sku, sum(abs(actual_qty - forecast_qty))::numeric / nullif(sum(actual_qty), 0) AS wmape
  FROM weekly
  GROUP BY sku
),
-- WMAPE를 거점 합산 후 (주, SKU) 단위 오차로 계산한 값
wmape_by_sku_week AS (
  SELECT sku, sum(abs(actual_qty - forecast_qty))::numeric / nullif(sum(actual_qty), 0) AS wmape
  FROM (
    SELECT sku, week_start, sum(actual_qty) AS actual_qty, sum(forecast_qty) AS forecast_qty
    FROM weekly
    GROUP BY sku, week_start
  ) w
  GROUP BY sku
),
checks (ord, name, ok) AS (
  SELECT 1, 'rows_product', (SELECT count(*) FROM scm.product) = 50
  UNION ALL SELECT 2, 'rows_location', (SELECT count(*) FROM scm.location) = 5
  UNION ALL SELECT 3, 'rows_sales_daily', (SELECT count(*) FROM scm.sales_daily) = 22750
  UNION ALL SELECT 4, 'rows_inventory_daily', (SELECT count(*) FROM scm.inventory_daily) = 22750
  UNION ALL SELECT 5, 'rows_forecast_weekly', (SELECT count(*) FROM scm.forecast_weekly) = 3250
  UNION ALL SELECT 6, 'rows_supply_policy', (SELECT count(*) FROM scm.supply_policy) = 250
  UNION ALL SELECT 7, 'on_hand_not_negative',
    (SELECT count(*) FROM scm.inventory_daily WHERE on_hand < 0) = 0
  UNION ALL SELECT 8, 'sales_date_range',
    (SELECT min(sale_date) = DATE '2026-08-10' AND max(sale_date) = DATE '2026-11-08' FROM scm.sales_daily)
  UNION ALL SELECT 9, 'inventory_date_range',
    (SELECT min(snap_date) = DATE '2026-08-10' AND max(snap_date) = DATE '2026-11-08' FROM scm.inventory_daily)
  UNION ALL SELECT 10, 'forecast_week_range',
    (SELECT min(week_start) = DATE '2026-08-10' AND max(week_start) = DATE '2026-11-02'
            AND count(DISTINCT week_start) = 13
            AND bool_and(extract(isodow FROM week_start) = 1)
     FROM scm.forecast_weekly)
  UNION ALL SELECT 11, 'promo_spike_sku_min_3', (SELECT count(*) FROM promo_sku) >= 3
  UNION ALL SELECT 12, 'stockout_pair_min_3', (SELECT count(*) FROM stockout_pair) >= 3
  UNION ALL SELECT 13, 'below_safety_stock_pair_3_to_30',
    (SELECT count(*) FROM as_of WHERE on_hand < safety_stock) BETWEEN 3 AND 30
  UNION ALL SELECT 14, 'reorder_needed_pair_min_3',
    (SELECT count(*) FROM as_of WHERE on_hand + in_transit <= reorder_point) >= 3
  UNION ALL SELECT 15, 'wmape_over_0_4_sku_min_3_by_row',
    (SELECT count(*) FROM wmape_by_row WHERE wmape > 0.4) >= 3
  UNION ALL SELECT 16, 'wmape_over_0_4_sku_min_3_by_sku_week',
    (SELECT count(*) FROM wmape_by_sku_week WHERE wmape > 0.4) >= 3
  UNION ALL SELECT 17, 'fk_orphans_sales_daily',
    (SELECT count(*) FROM scm.sales_daily s
     WHERE NOT EXISTS (SELECT 1 FROM scm.product p WHERE p.sku = s.sku)
        OR NOT EXISTS (SELECT 1 FROM scm.location l WHERE l.location_id = s.location_id)) = 0
  UNION ALL SELECT 18, 'fk_orphans_inventory_daily',
    (SELECT count(*) FROM scm.inventory_daily i
     WHERE NOT EXISTS (SELECT 1 FROM scm.product p WHERE p.sku = i.sku)
        OR NOT EXISTS (SELECT 1 FROM scm.location l WHERE l.location_id = i.location_id)) = 0
  UNION ALL SELECT 19, 'fk_orphans_forecast_weekly',
    (SELECT count(*) FROM scm.forecast_weekly f
     WHERE NOT EXISTS (SELECT 1 FROM scm.product p WHERE p.sku = f.sku)
        OR NOT EXISTS (SELECT 1 FROM scm.location l WHERE l.location_id = f.location_id)) = 0
  UNION ALL SELECT 20, 'fk_orphans_supply_policy',
    (SELECT count(*) FROM scm.supply_policy sp
     WHERE NOT EXISTS (SELECT 1 FROM scm.product p WHERE p.sku = sp.sku)
        OR NOT EXISTS (SELECT 1 FROM scm.location l WHERE l.location_id = sp.location_id)) = 0
  UNION ALL SELECT 21, 'scm_ro_no_temp_privilege',
    NOT has_database_privilege('scm_ro', current_database(), 'TEMPORARY')
  UNION ALL SELECT 22, 'scm_ro_no_public_schema_privilege',
    NOT has_schema_privilege('scm_ro', 'public', 'USAGE') AND NOT has_schema_privilege('scm_ro', 'public', 'CREATE')
  UNION ALL SELECT 23, 'scm_ro_no_app_schema_privilege',
    NOT has_schema_privilege('scm_ro', 'app', 'USAGE')
)
SELECT name, coalesce(ok, false) AS ok
FROM checks
ORDER BY ord;
