-- 실행 143(v1, 홀드아웃 20문항)의 미분류 오답에 확정한 error_type을 적용한다.
-- 자동 분류(ACTION_MISMATCH, SQL_EXECUTION_ERROR, TOOL_REJECTED에 붙는 TOOL_ARGUMENT)는 평가 실행기가 기록한다.
-- 원인이 여러 개인 문항은 기존 실행과 같은 기준으로 하나만 붙였다(지표 문항은 지표 계산, 그 밖에는 먼저 걸리는 것).
-- 실행: docker compose exec -T db psql -U scm_app -d scm -f - < eval/reports/run-143-v1-error-types.sql

-- JOIN_AGGREGATION: 부산센터 조건을 빼고 SKU 단위(전 거점 합산)로 계산
UPDATE app.eval_result SET error_type = 'JOIN_AGGREGATION' WHERE run_id = 143 AND question_id IN ('Q51');
-- METRIC_DEFINITION: Q52·Q56은 AVG(qty)(Q52는 기간도 15일), Q57은 재고에 in_transit을 더하고 판매는 SKU 전체로 계산, Q60은 주 단위 실적 없이 일별 판매와 예측을 직접 비교
UPDATE app.eval_result SET error_type = 'METRIC_DEFINITION' WHERE run_id = 143 AND question_id IN ('Q52', 'Q56', 'Q57', 'Q60');

SELECT count(*) AS not_classified FROM app.eval_result
WHERE run_id = 143 AND NOT correct
  AND (error_type IS NULL OR error_type NOT IN ('SCHEMA_MISUNDERSTANDING', 'METRIC_DEFINITION', 'DATE_RANGE',
       'JOIN_AGGREGATION', 'ACTION_MISMATCH', 'FORMAT_ERROR', 'SQL_EXECUTION_ERROR', 'TOOL_SELECTION', 'TOOL_ARGUMENT'));
