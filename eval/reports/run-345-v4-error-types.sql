-- 실행 345(v4, 새 세트 eval/golden_set_v5.json 40문항)의 미분류 오답에 error_type을 적용한다.
-- 자동 분류(ACTION_MISMATCH, SQL_EXECUTION_ERROR, TOOL_ARGUMENT)는 평가 실행기가 기록한다.
-- 실행: docker compose exec -T db psql -U scm_app -d scm -f - < eval/reports/run-345-v4-error-types.sql

-- Q124 "9월"을 2023-09로 풀었다.
UPDATE app.eval_result SET error_type = 'DATE_RANGE' WHERE run_id = 345 AND question_id = 'Q124';
-- Q134 분자에 in_transit을 더했고 판매량을 거점으로 거르지 않았다.
UPDATE app.eval_result SET error_type = 'METRIC_DEFINITION' WHERE run_id = 345 AND question_id = 'Q134';
-- Q135 9월 한 달 일평균을 최근 30일 일평균 도구로 보냈다(not_for에 적힌 경우).
UPDATE app.eval_result SET error_type = 'TOOL_SELECTION' WHERE run_id = 345 AND question_id = 'Q135';
-- Q138 "긴 쪽으로", Q139 "제일 긴"에 order asc를 줬다.
UPDATE app.eval_result SET error_type = 'TOOL_ARGUMENT' WHERE run_id = 345 AND question_id IN ('Q138', 'Q139');

SELECT count(*) AS not_classified FROM app.eval_result WHERE run_id = 345 AND NOT correct AND error_type IS NULL;
