-- 실행 346(v5, 새 세트 eval/golden_set_v5.json 40문항)의 미분류 오답에 error_type을 적용한다.
-- 자동 분류(ACTION_MISMATCH, SQL_EXECUTION_ERROR, TOOL_ARGUMENT)는 평가 실행기가 기록한다.
-- 실행: docker compose exec -T db psql -U scm_app -d scm -f - < eval/reports/run-346-v5-error-types.sql

-- Q124 "9월"을 2023-09로 풀었다(v4와 같은 SQL).
UPDATE app.eval_result SET error_type = 'DATE_RANGE' WHERE run_id = 346 AND question_id = 'Q124';
-- Q126 라우터는 v4처럼 days_of_supply(대구센터, SKU-031)를 골랐으나 점검이 "지표 낱말 없음"으로 SQL 단계에 넘겼다.
--      SQL은 거점을 거르지 않고 최근 8일로 나눴다. Q134는 v4와 같은 SQL.
UPDATE app.eval_result SET error_type = 'METRIC_DEFINITION' WHERE run_id = 346 AND question_id IN ('Q126', 'Q134');
-- Q135 9월 한 달 일평균을 최근 30일 일평균 도구로 보냈다(점검이 잡지 못함).
UPDATE app.eval_result SET error_type = 'TOOL_SELECTION' WHERE run_id = 346 AND question_id = 'Q135';
-- Q136 인자 검증 실패로 SQL 단계에 넘겼으나 SQL이 센터별로 묶지 않고 하루치만 셌다.
UPDATE app.eval_result SET error_type = 'JOIN_AGGREGATION' WHERE run_id = 346 AND question_id = 'Q136';
-- Q138 "긴 쪽으로"에 order asc를 줬고 점검의 정렬 교정이 이 표현을 잡지 못했다.
UPDATE app.eval_result SET error_type = 'TOOL_ARGUMENT' WHERE run_id = 346 AND question_id = 'Q138';

SELECT count(*) AS not_classified FROM app.eval_result WHERE run_id = 346 AND NOT correct AND error_type IS NULL;
