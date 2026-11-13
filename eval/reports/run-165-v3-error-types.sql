-- 실행 165(v3, 홀드아웃 세트, 후검증기 수정 후 재측정)의 미분류 오답에 error_type을 적용한다. 문항별 경로, 도구 인자, SQL, 답변이 실행 145과 같아 분류도 같다.
-- 자동 분류(ACTION_MISMATCH, SQL_EXECUTION_ERROR, TOOL_REJECTED에 붙는 TOOL_ARGUMENT)는 평가 실행기가 기록한다.
-- 원인이 여러 개인 문항은 기존 실행과 같은 기준으로 하나만 붙였다(지표 문항은 지표 계산, 그 밖에는 먼저 걸리는 것).
-- 실행: docker compose exec -T db psql -U scm_app -d scm -f - < eval/reports/run-165-v3-error-types.sql

-- METRIC_DEFINITION: 셋 다 SQL 경로. Q52는 도구(avg_daily_sales)로 답할 수 있는 질문인데 라우터가 SQL로 보냈고 SQL 단계가 AVG(qty)와 15일 기간을 씀. Q56·Q57은 도구로 표현할 수 없어 SQL로 간 것은 맞지만 Q56은 AVG(qty), Q57은 재고에 in_transit을 더함
UPDATE app.eval_result SET error_type = 'METRIC_DEFINITION' WHERE run_id = 165 AND question_id IN ('Q52', 'Q56', 'Q57');

SELECT count(*) AS not_classified FROM app.eval_result
WHERE run_id = 165 AND NOT correct
  AND (error_type IS NULL OR error_type NOT IN ('SCHEMA_MISUNDERSTANDING', 'METRIC_DEFINITION', 'DATE_RANGE',
       'JOIN_AGGREGATION', 'ACTION_MISMATCH', 'FORMAT_ERROR', 'SQL_EXECUTION_ERROR', 'TOOL_SELECTION', 'TOOL_ARGUMENT'));
