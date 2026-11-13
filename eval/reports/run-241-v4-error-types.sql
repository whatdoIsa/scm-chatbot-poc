-- 실행 241(v4, 홀드아웃 20문항)의 미분류 오답에 error_type을 적용한다.
-- 실행: docker compose exec -T db psql -U scm_app -d scm -f - < eval/reports/run-241-v4-error-types.sql

-- METRIC_DEFINITION: Q57은 v3(실행 165)과 같은 SQL. 재고보유일수의 분자에 in_transit을 더함. 지표 값에 조건이 붙어 도구로 표현할 수 없어 SQL로 간 것은 맞다
UPDATE app.eval_result SET error_type = 'METRIC_DEFINITION' WHERE run_id = 241 AND question_id IN ('Q57');

-- TOOL_ARGUMENT: Q60 "예측이 가장 잘 맞은 품목 3개"를 맞는 도구(forecast_accuracy ranking, top_n 3)로 보냈지만 order를 asc로 줌.
-- v3은 desc로 맞혔다. 라우터 개발 세트에서도 보였던 정렬 방향의 흔들림이다
UPDATE app.eval_result SET error_type = 'TOOL_ARGUMENT' WHERE run_id = 241 AND question_id IN ('Q60');
