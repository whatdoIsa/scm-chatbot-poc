-- 실행 22(v1)의 미분류 오답에 확정한 error_type을 적용한다. 자동 분류(ACTION_MISMATCH, SQL_EXECUTION_ERROR)는 평가 실행기가 기록한다.
-- 실행: docker compose exec -T db psql -U scm_app -d scm -f - < eval/reports/run-22-v1-error-types.sql

-- JOIN_AGGREGATION: Q01은 거점 합산 없이 5행 반환, Q15는 서울센터 조건을 빼고 SKU 단위로 계산
UPDATE app.eval_result SET error_type = 'JOIN_AGGREGATION' WHERE run_id = 22 AND question_id IN ('Q01', 'Q15');
-- DATE_RANGE: Q13은 최근 30일을 10-08부터 잡아 32일
UPDATE app.eval_result SET error_type = 'DATE_RANGE' WHERE run_id = 22 AND question_id IN ('Q13');
-- METRIC_DEFINITION: Q16은 기준일 하루만으로 결품률 계산, Q20은 AVG(qty), Q21은 주 단위 실적 없이 일별 판매와 예측을 직접 비교
UPDATE app.eval_result SET error_type = 'METRIC_DEFINITION' WHERE run_id = 22 AND question_id IN ('Q16', 'Q20', 'Q21');

SELECT count(*) AS not_classified FROM app.eval_result
WHERE run_id = 22 AND NOT correct
  AND (error_type IS NULL OR error_type NOT IN ('SCHEMA_MISUNDERSTANDING', 'METRIC_DEFINITION', 'DATE_RANGE',
       'JOIN_AGGREGATION', 'ACTION_MISMATCH', 'FORMAT_ERROR', 'SQL_EXECUTION_ERROR'));
