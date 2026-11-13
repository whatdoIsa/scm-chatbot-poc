-- 실행 142(v0, 홀드아웃 20문항)의 미분류 오답에 확정한 error_type을 적용한다.
-- 자동 분류(ACTION_MISMATCH, SQL_EXECUTION_ERROR, TOOL_REJECTED에 붙는 TOOL_ARGUMENT)는 평가 실행기가 기록한다.
-- 원인이 여러 개인 문항은 기존 실행과 같은 기준으로 하나만 붙였다(지표 문항은 지표 계산, 그 밖에는 먼저 걸리는 것).
-- 실행: docker compose exec -T db psql -U scm_app -d scm -f - < eval/reports/run-142-v0-error-types.sql

-- METRIC_DEFINITION: 지표를 PRD 5.3과 다르게 계산. Q51은 재고만 반환, Q52·Q56은 AVG(qty), Q53·Q58은 결품을 qty < 0이나 on_hand < 0으로 정의, Q54·Q60은 주 단위 WMAPE가 아님, Q57은 safety_stock과 lead_time_days를 비교. 대부분 CURRENT_DATE나 없는 거점 ID도 함께 썼다
UPDATE app.eval_result SET error_type = 'METRIC_DEFINITION' WHERE run_id = 142 AND question_id IN ('Q51', 'Q52', 'Q53', 'Q54', 'Q56', 'Q57', 'Q58', 'Q60');
-- DATE_RANGE: Q61·Q62는 기준일 조건 없이 전 기간 재고를 합산, Q64·Q66은 CURRENT_DATE, Q65는 연도를 2023으로 추측
UPDATE app.eval_result SET error_type = 'DATE_RANGE' WHERE run_id = 142 AND question_id IN ('Q61', 'Q62', 'Q64', 'Q65', 'Q66');

SELECT count(*) AS not_classified FROM app.eval_result
WHERE run_id = 142 AND NOT correct
  AND (error_type IS NULL OR error_type NOT IN ('SCHEMA_MISUNDERSTANDING', 'METRIC_DEFINITION', 'DATE_RANGE',
       'JOIN_AGGREGATION', 'ACTION_MISMATCH', 'FORMAT_ERROR', 'SQL_EXECUTION_ERROR', 'TOOL_SELECTION', 'TOOL_ARGUMENT'));
