-- 실행 294(v5, 심화 20문항)의 미분류 오답에 error_type을 적용한다.
-- 자동 분류: Q35, Q38 SQL_EXECUTION_ERROR. Q38은 v4에서 TOOL_REJECTED였던 호출을 v5가 SQL 단계로 넘겼고, SQL 단계가 COUNT(DISTINCT a, b)로 실패했다.
-- 실행: docker compose exec -T db psql -U scm_app -d scm -f - < eval/reports/run-294-v5-error-types.sql

-- METRIC_DEFINITION: Q36 점검이 도구 호출(검증 실패)을 SQL 단계로 넘겼고, SQL 단계가 카테고리별 일평균을 AVG(qty)로 계산
UPDATE app.eval_result SET error_type = 'METRIC_DEFINITION' WHERE run_id = 294 AND question_id IN ('Q36');

-- METRIC_DEFINITION: Q39 점검이 거점 순위를 SQL 단계로 넘겼고, SQL 단계가 결품률 대신 기준일의 재고 0 건수로 거점을 고름
UPDATE app.eval_result SET error_type = 'METRIC_DEFINITION' WHERE run_id = 294 AND question_id IN ('Q39');

-- JOIN_AGGREGATION: Q44, Q45는 v4(실행 240)와 같은 SQL
UPDATE app.eval_result SET error_type = 'JOIN_AGGREGATION' WHERE run_id = 294 AND question_id IN ('Q44', 'Q45');
