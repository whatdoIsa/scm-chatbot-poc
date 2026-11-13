-- 실행 240(v4, 심화 20문항)의 미분류 오답에 error_type을 적용한다.
-- 자동 분류: Q35 SQL_EXECUTION_ERROR, Q36·Q38 TOOL_ARGUMENT(TOOL_REJECTED). Q36은 카테고리별 집계라 도구가 표현할 수 없는 질문을
-- ranking(top_n 없음)으로 보낸 것이고, Q38은 결품이 있었던 SKU-거점 수를 stockout_rate의 count로 요청해 거부됐다.
-- 실행: docker compose exec -T db psql -U scm_app -d scm -f - < eval/reports/run-240-v4-error-types.sql

-- TOOL_SELECTION: Q39 결품률이 가장 높은 '거점'을 품목 순위(ranking)로 답함. v3과 같은 오답
UPDATE app.eval_result SET error_type = 'TOOL_SELECTION' WHERE run_id = 240 AND question_id IN ('Q39');

-- JOIN_AGGREGATION: Q44는 v3과 같은 SQL(주별 차이를 품목 전체로 묶지 못함). Q45는 7일 판매 합이 0인 SKU-거점 대신
-- 하루라도 qty = 0인 행을 찾음(v3은 stockout_rate list로 보내 TOOL_ARGUMENT)
UPDATE app.eval_result SET error_type = 'JOIN_AGGREGATION' WHERE run_id = 240 AND question_id IN ('Q44', 'Q45');
