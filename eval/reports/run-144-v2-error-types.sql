-- 실행 144(v2, 홀드아웃 20문항)의 미분류 오답에 확정한 error_type을 적용한다.
-- 자동 분류(ACTION_MISMATCH, SQL_EXECUTION_ERROR, TOOL_REJECTED에 붙는 TOOL_ARGUMENT)는 평가 실행기가 기록한다.
-- 원인이 여러 개인 문항은 기존 실행과 같은 기준으로 하나만 붙였다(지표 문항은 지표 계산, 그 밖에는 먼저 걸리는 것).
-- 실행: docker compose exec -T db psql -U scm_app -d scm -f - < eval/reports/run-144-v2-error-types.sql

-- JOIN_AGGREGATION: 부산센터 조건을 빼고 SKU 단위(전 거점 합산)로 계산
UPDATE app.eval_result SET error_type = 'JOIN_AGGREGATION' WHERE run_id = 144 AND question_id IN ('Q51');
-- METRIC_DEFINITION: Q52·Q56은 AVG(qty), Q57은 on_hand에 28을 곱함. Q53은 산식과 기간은 맞지만 비율이 아니라 100을 곱한 백분율(10.32)을 반환해 0.1% 허용 오차를 벗어남
UPDATE app.eval_result SET error_type = 'METRIC_DEFINITION' WHERE run_id = 144 AND question_id IN ('Q52', 'Q53', 'Q56', 'Q57');
-- SCHEMA_MISUNDERSTANDING: 권역 값을 '영남'으로 조회(저장된 값은 '영남권'). 날짜는 맞음
UPDATE app.eval_result SET error_type = 'SCHEMA_MISUNDERSTANDING' WHERE run_id = 144 AND question_id IN ('Q64');

SELECT count(*) AS not_classified FROM app.eval_result
WHERE run_id = 144 AND NOT correct
  AND (error_type IS NULL OR error_type NOT IN ('SCHEMA_MISUNDERSTANDING', 'METRIC_DEFINITION', 'DATE_RANGE',
       'JOIN_AGGREGATION', 'ACTION_MISMATCH', 'FORMAT_ERROR', 'SQL_EXECUTION_ERROR', 'TOOL_SELECTION', 'TOOL_ARGUMENT'));
