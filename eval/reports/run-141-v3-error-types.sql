-- 실행 141(v3, 심화 20문항)의 미분류 오답에 확정한 error_type을 적용한다.
-- 자동 분류(ACTION_MISMATCH, SQL_EXECUTION_ERROR, TOOL_REJECTED에 붙는 TOOL_ARGUMENT)는 평가 실행기가 기록한다.
-- 실행: docker compose exec -T db psql -U scm_app -d scm -f - < eval/reports/run-141-v3-error-types.sql

-- TOOL_SELECTION: Q34는 재고 0인 조합을 below_safety_stock(list)로, Q39는 거점 순위를 품목 순위 도구로, Q40은 리드타임 조건을 표현할 수 없는 below_safety_stock(count)로 보냄
UPDATE app.eval_result SET error_type = 'TOOL_SELECTION' WHERE run_id = 141 AND question_id IN ('Q34', 'Q39', 'Q40');
-- METRIC_DEFINITION: SQL 경로(경로 선택은 맞음). 일평균을 AVG(qty)로 계산
UPDATE app.eval_result SET error_type = 'METRIC_DEFINITION' WHERE run_id = 141 AND question_id IN ('Q36');
-- JOIN_AGGREGATION: SQL 경로. 주별 실적을 맞추지 않고 전체 기간 판매를 모든 주에 조인
UPDATE app.eval_result SET error_type = 'JOIN_AGGREGATION' WHERE run_id = 141 AND question_id IN ('Q44');
-- 자동 기록된 TOOL_ARGUMENT 2건(Q38, Q45)은 stockout_rate가 지원하지 않는 output(count, list)을 요청해 거부됐다.
-- 두 질문 모두 도구로 표현할 수 없는 질문이라 밑바탕의 원인은 도구 선택이지만, 자동 분류 규칙(PRD 10.6)대로 둔다.
-- 두 호출 모두 질문에 없는 sku(SKU-003, SKU-007)가 들어 있다.

SELECT count(*) AS not_classified FROM app.eval_result
WHERE run_id = 141 AND NOT correct
  AND (error_type IS NULL OR error_type NOT IN ('SCHEMA_MISUNDERSTANDING', 'METRIC_DEFINITION', 'DATE_RANGE',
       'JOIN_AGGREGATION', 'ACTION_MISMATCH', 'FORMAT_ERROR', 'SQL_EXECUTION_ERROR', 'TOOL_SELECTION', 'TOOL_ARGUMENT'));
