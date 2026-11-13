-- 실행 244(v3, 새 세트 40문항)의 미분류 오답에 error_type을 적용한다. v4(실행 245)와 원인별로 비교하려고 분류했다.
-- 자동 분류: ACTION_MISMATCH(Q105, Q108, Q109, Q110), SQL_EXECUTION_ERROR(Q79, Q80), TOOL_ARGUMENT(Q81 질문에 없는 SKU-003, Q100).
-- 실행: docker compose exec -T db psql -U scm_app -d scm -f - < eval/reports/run-244-v3-error-types.sql

-- METRIC_DEFINITION: 조건이 붙어 SQL로 간 지표 질문. Q76 일평균을 AVG(qty)로, Q86 예측 정확도를 행 단위 오차 평균으로 계산
UPDATE app.eval_result SET error_type = 'METRIC_DEFINITION' WHERE run_id = 244 AND question_id IN ('Q76', 'Q86');

-- TOOL_SELECTION: Q88 거점 순위를 품목 순위로, Q98 재주문점 값 조회를 재주문 필요 도구로, Q99 예측 수량을 예측 정확도로 답함
UPDATE app.eval_result SET error_type = 'TOOL_SELECTION' WHERE run_id = 244 AND question_id IN ('Q88', 'Q98', 'Q99');

-- TOOL_ARGUMENT: Q83 맞는 도구(days_of_supply ranking)에 order asc. v4와 같은 오답
UPDATE app.eval_result SET error_type = 'TOOL_ARGUMENT' WHERE run_id = 244 AND question_id IN ('Q83');

-- JOIN_AGGREGATION: Q78 영남권 조건을 빼고 전체를 집계, Q82·Q95는 v4(실행 245)와 같은 SQL
UPDATE app.eval_result SET error_type = 'JOIN_AGGREGATION' WHERE run_id = 244 AND question_id IN ('Q78', 'Q82', 'Q95');

-- DATE_RANGE: Q93 연도를 2023으로 씀(v4와 같은 SQL)
UPDATE app.eval_result SET error_type = 'DATE_RANGE' WHERE run_id = 244 AND question_id IN ('Q93');
