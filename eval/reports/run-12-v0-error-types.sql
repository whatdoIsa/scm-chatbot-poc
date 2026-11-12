-- 실행 12(v0)의 미분류 오답 18건에 사람이 확정한 error_type을 적용한다.
-- 자동 분류된 5건(ACTION_MISMATCH: Q25, Q29, Q30 / SQL_EXECUTION_ERROR: Q18, Q19)은 평가 실행기가 기록한다.
-- 실행: docker compose exec -T db psql -U scm_app -d scm -f - < eval/reports/run-12-v0-error-types.sql

-- DATE_RANGE: 기준일(2026-11-08) 대신 CURRENT_DATE를 쓰거나 날짜 조건이 없거나 연도를 추측
UPDATE app.eval_result SET error_type = 'DATE_RANGE'
WHERE run_id = 12 AND question_id IN ('Q01', 'Q02', 'Q05', 'Q07', 'Q08', 'Q09', 'Q10', 'Q13', 'Q14');

-- METRIC_DEFINITION: 지표를 PRD 5.3과 다르게 계산
UPDATE app.eval_result SET error_type = 'METRIC_DEFINITION'
WHERE run_id = 12 AND question_id IN ('Q15', 'Q16', 'Q17', 'Q20', 'Q21', 'Q24');

-- SCHEMA_MISUNDERSTANDING: SKU 표기를 정규화하지 않거나 거점 ID에 없는 값을 사용
UPDATE app.eval_result SET error_type = 'SCHEMA_MISUNDERSTANDING'
WHERE run_id = 12 AND question_id IN ('Q04', 'Q11', 'Q12');

-- 확인: 0이 나와야 한다
SELECT count(*) AS not_classified FROM app.eval_result
WHERE run_id = 12 AND NOT correct
  AND (error_type IS NULL OR error_type NOT IN ('SCHEMA_MISUNDERSTANDING', 'METRIC_DEFINITION', 'DATE_RANGE',
       'JOIN_AGGREGATION', 'ACTION_MISMATCH', 'FORMAT_ERROR', 'SQL_EXECUTION_ERROR'));
