-- 실행 295(v5, 홀드아웃 20문항)의 미분류 오답에 error_type을 적용한다.
-- 실행: docker compose exec -T db psql -U scm_app -d scm -f - < eval/reports/run-295-v5-error-types.sql
-- METRIC_DEFINITION: Q57은 v4(실행 241)와 같은 SQL. 재고보유일수의 분자에 in_transit을 더함
UPDATE app.eval_result SET error_type = 'METRIC_DEFINITION' WHERE run_id = 295 AND question_id IN ('Q57');
