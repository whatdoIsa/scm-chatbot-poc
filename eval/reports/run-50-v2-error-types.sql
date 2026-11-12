-- 실행 50(v2, 기본 30문항)의 미분류 오답에 확정한 error_type을 적용한다. 자동 분류(ACTION_MISMATCH, SQL_EXECUTION_ERROR)는 평가 실행기가 기록한다.
-- 실행: docker compose exec -T db psql -U scm_app -d scm -f - < eval/reports/run-50-v2-error-types.sql

UPDATE app.eval_result SET error_type = 'JOIN_AGGREGATION' WHERE run_id = 50 AND question_id IN ('Q01', 'Q05', 'Q24');
UPDATE app.eval_result SET error_type = 'DATE_RANGE' WHERE run_id = 50 AND question_id IN ('Q13');
UPDATE app.eval_result SET error_type = 'METRIC_DEFINITION' WHERE run_id = 50 AND question_id IN ('Q16', 'Q20', 'Q21');

SELECT count(*) AS not_classified FROM app.eval_result
WHERE run_id = 50 AND NOT correct
  AND (error_type IS NULL OR error_type NOT IN ('SCHEMA_MISUNDERSTANDING', 'METRIC_DEFINITION', 'DATE_RANGE',
       'JOIN_AGGREGATION', 'ACTION_MISMATCH', 'FORMAT_ERROR', 'SQL_EXECUTION_ERROR'));
