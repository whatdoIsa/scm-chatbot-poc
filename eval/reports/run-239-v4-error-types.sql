-- 실행 239(v4, 기본 30문항)의 미분류 오답에 error_type을 적용한다.
-- 자동 분류(ACTION_MISMATCH, SQL_EXECUTION_ERROR, TOOL_REJECTED에 붙는 TOOL_ARGUMENT)는 평가 실행기가 기록한다(Q05, Q25).
-- 원인이 여러 개인 문항은 v3 실행과 같은 기준으로 하나만 붙였다(지표 문항은 지표 계산, 그 밖에는 먼저 걸리는 것).
-- 실행: docker compose exec -T db psql -U scm_app -d scm -f - < eval/reports/run-239-v4-error-types.sql

-- JOIN_AGGREGATION: Q01은 v3(실행 163)과 같은 SQL. 거점별 행을 그대로 내고 SKU 단위로 합산하지 않음
UPDATE app.eval_result SET error_type = 'JOIN_AGGREGATION' WHERE run_id = 239 AND question_id IN ('Q01');

-- TOOL_SELECTION: 판매량 합계·출고량을 묻는 질문을 일평균 도구로 보냄. Q07은 '어제'를 days=1(최근 1일 = 기준일)로,
-- Q08·Q09·Q10은 7일 합계를 7일 일평균으로 답함. v3은 Q08만 틀렸고 Q07·Q09·Q10은 SQL로 맞혔다
UPDATE app.eval_result SET error_type = 'TOOL_SELECTION' WHERE run_id = 239 AND question_id IN ('Q07', 'Q08', 'Q09', 'Q10');

-- DATE_RANGE: Q13은 v3과 같은 SQL. 최근 30일을 32일(10-08 ~ 11-08)로 잡음(정답은 10-10 ~ 11-08)
UPDATE app.eval_result SET error_type = 'DATE_RANGE' WHERE run_id = 239 AND question_id IN ('Q13');
