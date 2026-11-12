-- SCM 시드 데이터. 난수 대신 hashtextextended(키, 42)로 값을 만들어 매번 같은 결과가 나온다.
-- 판매·재고 기간은 2026-08-10(월) ~ 2026-11-08(일) 91일, 예측은 주 시작일 2026-08-10 ~ 2026-11-02의 13주.
-- 보조 함수와 파라미터 테이블은 pg_temp에 만들어 초기화 세션이 끝나면 남지 않는다.

BEGIN;

-- 키마다 고정된 [0, 1) 값. 부동소수 오차를 피하려고 numeric으로 계산한다.
CREATE FUNCTION pg_temp.u(key text) RETURNS numeric
LANGUAGE sql IMMUTABLE AS $$
  SELECT (abs(hashtextextended(key, 42) % 1000000))::numeric / 1000000
$$;

-- 1. 거점

INSERT INTO scm.location (location_id, name, region) VALUES
  ('LOC-01', '서울센터', '수도권'),
  ('LOC-02', '부산센터', '영남권'),
  ('LOC-03', '대전센터', '충청권'),
  ('LOC-04', '광주센터', '호남권'),
  ('LOC-05', '대구센터', '영남권');

CREATE TEMP TABLE seed_location (
  location_id varchar(8) PRIMARY KEY,
  demand_factor numeric NOT NULL
) ON COMMIT DROP;

INSERT INTO seed_location VALUES
  ('LOC-01', 1.5), ('LOC-02', 1.1), ('LOC-03', 0.8), ('LOC-04', 0.7), ('LOC-05', 0.9);

-- 2. 품목: 카테고리 5개 x 10개. ABC 등급은 해시 순위로 A 10, B 15, C 25개를 배정한다.

WITH catalog (cat_no, category, names) AS (
  VALUES
    (1, '음료', ARRAY['생수 2L', '탄산수 500ml', '콜라 1.5L', '사이다 1.5L', '오렌지주스 1L',
                      '캔커피 240ml', '녹차 500ml', '이온음료 600ml', '두유 190ml', '에너지드링크 250ml']),
    (2, '스낵', ARRAY['감자칩 60g', '새우과자 90g', '초코파이 12입', '비스킷 100g', '팝콘 80g',
                      '견과믹스 200g', '젤리 70g', '캐러멜 100g', '쌀과자 120g', '크래커 150g']),
    (3, '생활용품', ARRAY['화장지 30롤', '키친타월 6롤', '물티슈 100매', '주방세제 500ml', '세탁세제 3L',
                          '섬유유연제 2L', '지퍼백 50매', '종이컵 100개', '고무장갑', '수세미 5입']),
    (4, '냉동식품', ARRAY['냉동만두 1kg', '냉동피자', '냉동볶음밥 450g', '치킨너겟 500g', '냉동새우 300g',
                          '아이스크림 바', '냉동핫도그 5입', '감자튀김 1kg', '냉동돈까스 600g', '떡갈비 400g']),
    (5, '개인위생', ARRAY['샴푸 500ml', '린스 500ml', '바디워시 500ml', '치약 120g', '칫솔 4입',
                          '손세정제 250ml', '비누 3입', '면도기', '마스크 50매', '핸드크림 50ml'])
),
numbered AS (
  SELECT 'SKU-' || lpad(((c.cat_no - 1) * 10 + n.ord)::text, 3, '0') AS sku, n.name, c.category
  FROM catalog c
  CROSS JOIN LATERAL unnest(c.names) WITH ORDINALITY AS n (name, ord)
),
ranked AS (
  SELECT *, row_number() OVER (ORDER BY pg_temp.u('abc|' || sku), sku) AS abc_rank
  FROM numbered
)
INSERT INTO scm.product (sku, name, category, unit_cost, abc_class)
SELECT sku, name, category,
       round((500 + 29500 * pg_temp.u('cost|' || sku)) / 10) * 10,
       CASE WHEN abc_rank <= 10 THEN 'A' WHEN abc_rank <= 25 THEN 'B' ELSE 'C' END
FROM ranked;

-- 3. 품목별 수요 파라미터와 심어 둘 패턴
--    프로모션: 시작일부터 7일간 수요 4배. 예측 편향: 예측이 크게 빗나가는 품목(WMAPE 0.4 초과).

CREATE TEMP TABLE seed_sku ON COMMIT DROP AS
SELECT p.sku,
       CASE p.abc_class
         WHEN 'A' THEN 30 + 30 * pg_temp.u('base|' || p.sku)
         WHEN 'B' THEN 12 + 14 * pg_temp.u('base|' || p.sku)
         ELSE 2 + 8 * pg_temp.u('base|' || p.sku)
       END AS base_demand,
       -0.15 + 0.40 * pg_temp.u('trend|' || p.sku) AS trend_slope,
       promo.promo_start,
       coalesce(bias.forecast_bias, 1.0) AS forecast_bias
FROM scm.product p
LEFT JOIN (VALUES
    ('SKU-005', DATE '2026-09-04'),
    ('SKU-017', DATE '2026-09-25'),
    ('SKU-029', DATE '2026-10-16'),
    ('SKU-042', DATE '2026-10-30')
  ) AS promo (sku, promo_start) USING (sku)
LEFT JOIN (VALUES
    ('SKU-008', 1.70),
    ('SKU-021', 0.45),
    ('SKU-033', 1.80),
    ('SKU-046', 0.40)
  ) AS bias (sku, forecast_bias) USING (sku);

-- 4. 공급 정책
--    평균 일수요 = 기준 수요 x 거점 계수 x 요일 계수 평균(7.5 / 7).
--    재주문점은 안전재고 + (리드타임 + 1일)치 수요. 발주가 전일 마감 기준으로 다음 날 아침에 나가기 때문이다.
--    공급 차질 기간에는 발주가 나가지 않아 결품 구간과 기준일의 안전재고 미달·재주문 필요가 생긴다.

CREATE TEMP TABLE seed_pair ON COMMIT DROP AS
WITH base AS (
  SELECT s.sku, l.location_id,
         s.base_demand * l.demand_factor AS daily_base,
         s.base_demand * l.demand_factor * 7.5 / 7 AS avg_daily,
         (2 + floor(6 * pg_temp.u('lead|' || s.sku || '|' || l.location_id)))::int AS lead_time_days
  FROM seed_sku s
  CROSS JOIN seed_location l
),
policy AS (
  SELECT *,
         ceil(avg_daily * (2 + 2 * pg_temp.u('safety|' || sku || '|' || location_id)))::int AS safety_stock,
         ceil(avg_daily * (7 + floor(8 * pg_temp.u('cover|' || sku || '|' || location_id))))::int AS order_qty
  FROM base
),
positioned AS (
  SELECT *, safety_stock + ceil(avg_daily * (lead_time_days + 1))::int AS reorder_point
  FROM policy
)
SELECT p.sku, p.location_id, p.daily_base, p.lead_time_days, p.safety_stock, p.reorder_point, p.order_qty,
       p.reorder_point
         + ceil(p.order_qty * (0.2 + 0.8 * pg_temp.u('init|' || p.sku || '|' || p.location_id)))::int AS init_on_hand,
       d.disruption_start, d.disruption_end
FROM positioned p
LEFT JOIN (VALUES
    ('SKU-011', 'LOC-02', DATE '2026-09-07', DATE '2026-09-30'),
    ('SKU-023', 'LOC-04', DATE '2026-09-21', DATE '2026-10-14'),
    ('SKU-036', 'LOC-01', DATE '2026-08-24', DATE '2026-09-16'),
    ('SKU-048', 'LOC-03', DATE '2026-10-05', DATE '2026-10-28'),
    ('SKU-002', 'LOC-05', DATE '2026-10-19', DATE '2026-11-08'),
    ('SKU-019', 'LOC-01', DATE '2026-10-19', DATE '2026-11-08'),
    ('SKU-040', 'LOC-02', DATE '2026-10-19', DATE '2026-11-08')
  ) AS d (sku, location_id, disruption_start, disruption_end) USING (sku, location_id);

INSERT INTO scm.supply_policy (sku, location_id, lead_time_days, safety_stock, reorder_point)
SELECT sku, location_id, lead_time_days, safety_stock, reorder_point
FROM seed_pair;

-- 5. 일별 판매와 재고
--    재고는 전일 재고 + 입고 - 판매로 하루씩 계산한다. 판매는 가용 재고를 넘지 않으므로 음수가 나오지 않는다.

CREATE FUNCTION pg_temp.seed_sales_inventory() RETURNS void
LANGUAGE plpgsql AS $$
DECLARE
  first_day CONSTANT date := DATE '2026-08-10';
  day_count CONSTANT int := 91;
  max_lead_time CONSTANT int := 7;
  promo_days CONSTANT int := 7;
  promo_multiplier CONSTANT numeric := 4.0;
  -- ISO 요일(월 = 1 ... 일 = 7)별 판매 계수
  dow_factor CONSTANT numeric[] := ARRAY[0.90, 0.90, 0.95, 1.00, 1.15, 1.35, 1.25];
  p record;
  arrivals int[];
  day_idx int;
  d date;
  slot int;
  on_hand int;
  in_transit int;
  promo_qty int;
  promo_in_transit int;
  promo_reserve int;
  base_demand int;
  demand int;
  sold int;
BEGIN
  FOR p IN
    SELECT sp.*, sk.trend_slope, sk.promo_start
    FROM seed_pair sp
    JOIN seed_sku sk USING (sku)
    ORDER BY sp.sku, sp.location_id
  LOOP
    -- arrivals[day_idx + 1] = 그날 아침에 도착하는 수량
    arrivals := array_fill(0, ARRAY[day_count + max_lead_time + 1]);
    on_hand := p.init_on_hand;
    in_transit := 0;
    promo_in_transit := 0;
    promo_reserve := 0;

    FOR day_idx IN 0 .. day_count - 1 LOOP
      d := first_day + day_idx;

      -- 프로모션이 끝나면 남은 사전 물량을 일반 재고로 돌린다.
      IF d = p.promo_start + promo_days THEN
        promo_reserve := 0;
      END IF;

      -- 아침 발주: 전일 마감 재고 위치(재고 + 입고 예정)가 재주문점 이하이면 발주한다.
      -- 프로모션 사전 물량은 증가분 수요에만 쓰이므로 재고 위치에서 뺀다. 넣으면 정기 발주가 밀려 결품이 난다.
      IF (on_hand - promo_reserve) + (in_transit - promo_in_transit) <= p.reorder_point
         AND NOT coalesce(d BETWEEN p.disruption_start AND p.disruption_end, false) THEN
        slot := day_idx + 1 + p.lead_time_days;
        arrivals[slot] := arrivals[slot] + p.order_qty;
        in_transit := in_transit + p.order_qty;
      END IF;

      -- 프로모션 시작일에 맞춰 도착하도록 사전 물량을 발주한다.
      IF p.promo_start IS NOT NULL AND d = p.promo_start - p.lead_time_days THEN
        promo_qty := ceil(p.daily_base * promo_days * (promo_multiplier - 1) * 1.2);
        slot := day_idx + 1 + p.lead_time_days;
        arrivals[slot] := arrivals[slot] + promo_qty;
        in_transit := in_transit + promo_qty;
        promo_in_transit := promo_qty;
      END IF;
      IF d = p.promo_start THEN
        promo_reserve := promo_in_transit;
        promo_in_transit := 0;
      END IF;

      on_hand := on_hand + arrivals[day_idx + 1];
      in_transit := in_transit - arrivals[day_idx + 1];

      base_demand := round(
        p.daily_base
        * dow_factor[extract(isodow FROM d)::int]
        * (1 + p.trend_slope * day_idx / 90.0)
        * (0.8 + 0.4 * pg_temp.u('noise|' || p.sku || '|' || p.location_id || '|' || day_idx))
      );
      demand := base_demand
        * CASE WHEN d >= p.promo_start AND d < p.promo_start + promo_days THEN promo_multiplier ELSE 1 END;
      sold := least(demand, on_hand);
      on_hand := on_hand - sold;
      -- 평소 수요를 넘는 판매분은 사전 물량에서 나간 것으로 본다.
      promo_reserve := promo_reserve - least(promo_reserve, greatest(sold - base_demand, 0));

      INSERT INTO scm.sales_daily (sale_date, sku, location_id, qty)
      VALUES (d, p.sku, p.location_id, sold);
      INSERT INTO scm.inventory_daily (snap_date, sku, location_id, on_hand, in_transit)
      VALUES (d, p.sku, p.location_id, on_hand, in_transit);
    END LOOP;
  END LOOP;
END
$$;

SELECT pg_temp.seed_sales_inventory();

-- 6. 주별 예측
--    요일 계수 합(7.5)과 주 중간일의 추세를 반영한 기대 수요에 품목 편향과 ±15% 오차를 곱한다.
--    프로모션은 예측에 반영하지 않는다.

INSERT INTO scm.forecast_weekly (week_start, sku, location_id, forecast_qty)
SELECT DATE '2026-08-10' + w * 7, sp.sku, sp.location_id,
       round(
         sp.daily_base * 7.5
         * (1 + sk.trend_slope * (w * 7 + 3) / 90.0)
         * sk.forecast_bias
         * (0.85 + 0.30 * pg_temp.u('forecast|' || sp.sku || '|' || sp.location_id || '|' || w))
       )::int
FROM generate_series(0, 12) AS w
CROSS JOIN seed_pair sp
JOIN seed_sku sk USING (sku);

COMMIT;
