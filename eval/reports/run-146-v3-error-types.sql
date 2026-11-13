-- 실행 146(v3, 기본 30문항 재측정)의 미분류 오답에 error_type을 적용한다. 문항별 경로, 도구 인자, SQL, 답변이 실행 140과 같아 분류도 같다.
-- 자동 분류(ACTION_MISMATCH, SQL_EXECUTION_ERROR, TOOL_REJECTED에 붙는 TOOL_ARGUMENT)는 평가 실행기가 기록한다.
-- 실행: docker compose exec -T db psql -U scm_app -d scm -f - < eval/reports/run-146-v3-error-types.sql

-- JOIN_AGGREGATION: SQL 경로. 거점 합산 없이 5행 반환(v1, v2와 같은 오답)
UPDATE app.eval_result SET error_type = 'JOIN_AGGREGATION' WHERE run_id = 146 AND question_id IN ('Q01');
-- TOOL_SELECTION: Q03은 재주문점 조회를 reorder_needed(count)로, Q05는 재고 0인 조합을 below_safety_stock(list)로, Q08은 판매량 합계를 avg_daily_sales로 보냄. 셋 다 도구의 지표가 질문과 다르다
UPDATE app.eval_result SET error_type = 'TOOL_SELECTION' WHERE run_id = 146 AND question_id IN ('Q03', 'Q05', 'Q08');
-- DATE_RANGE: SQL 경로. 최근 30일을 10-08부터 잡아 32일(v1, v2와 같은 오답)
UPDATE app.eval_result SET error_type = 'DATE_RANGE' WHERE run_id = 146 AND question_id IN ('Q13');
-- TOOL_ARGUMENT: 도구(below_safety_stock, count)는 맞지만 질문에 없는 sku SKU-003을 넣음. 라우터 예시에 나온 SKU다
UPDATE app.eval_result SET error_type = 'TOOL_ARGUMENT' WHERE run_id = 146 AND question_id IN ('Q17');

SELECT count(*) AS not_classified FROM app.eval_result
WHERE run_id = 146 AND NOT correct
  AND (error_type IS NULL OR error_type NOT IN ('SCHEMA_MISUNDERSTANDING', 'METRIC_DEFINITION', 'DATE_RANGE',
       'JOIN_AGGREGATION', 'ACTION_MISMATCH', 'FORMAT_ERROR', 'SQL_EXECUTION_ERROR', 'TOOL_SELECTION', 'TOOL_ARGUMENT'));
