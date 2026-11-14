-- 실행 293(v5, 기본 30문항)의 미분류 오답에 error_type을 적용한다. 두 문항 모두 v4(실행 239)와 같은 SQL이다.
-- 실행: docker compose exec -T db psql -U scm_app -d scm -f - < eval/reports/run-293-v5-error-types.sql
UPDATE app.eval_result SET error_type = 'JOIN_AGGREGATION' WHERE run_id = 293 AND question_id IN ('Q01');
UPDATE app.eval_result SET error_type = 'DATE_RANGE' WHERE run_id = 293 AND question_id IN ('Q13');
