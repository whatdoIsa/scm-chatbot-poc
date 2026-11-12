#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

docker compose exec -T db psql -U scm_app -d scm -v ON_ERROR_STOP=1 -At <<'EOSQL'
-- UNION ALL의 가지는 병렬 실행 시 순서가 바뀔 수 있어 출력 순서를 ord로 고정한다.
SELECT name, checksum
FROM (
  SELECT 1 AS ord, 'product' AS name, md5(string_agg(t::text, '' ORDER BY sku)) AS checksum FROM scm.product t
  UNION ALL
  SELECT 2, 'location', md5(string_agg(t::text, '' ORDER BY location_id)) FROM scm.location t
  UNION ALL
  SELECT 3, 'sales_daily', md5(string_agg(t::text, '' ORDER BY sale_date, sku, location_id)) FROM scm.sales_daily t
  UNION ALL
  SELECT 4, 'inventory_daily', md5(string_agg(t::text, '' ORDER BY snap_date, sku, location_id)) FROM scm.inventory_daily t
  UNION ALL
  SELECT 5, 'forecast_weekly', md5(string_agg(t::text, '' ORDER BY week_start, sku, location_id)) FROM scm.forecast_weekly t
  UNION ALL
  SELECT 6, 'supply_policy', md5(string_agg(t::text, '' ORDER BY sku, location_id)) FROM scm.supply_policy t
) c
ORDER BY ord;
EOSQL
