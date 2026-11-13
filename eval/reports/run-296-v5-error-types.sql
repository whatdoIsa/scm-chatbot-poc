-- 실행 296(v5, 새 세트 40문항)의 미분류 오답에 error_type을 적용한다.
-- 자동 분류: Q105 ACTION_MISMATCH. 점검이 되묻기 문항의 도구 호출을 SQL 단계로 넘겼고, SQL 단계도 되묻지 않고 재고 상태를 조회했다.
-- 실행: docker compose exec -T db psql -U scm_app -d scm -f - < eval/reports/run-296-v5-error-types.sql

-- METRIC_DEFINITION: Q88 점검이 거점 순위를 SQL 단계로 넘겼고, SQL 단계가 예측 정확도를 일별 판매와 주별 예측을 바로 비교해 계산
UPDATE app.eval_result SET error_type = 'METRIC_DEFINITION' WHERE run_id = 296 AND question_id IN ('Q88');

-- JOIN_AGGREGATION: Q99 점검이 예측 수량 질문을 SQL 단계로 넘겼고, SQL 단계가 거점별 행을 합산하지 않음.
-- Q81, Q82, Q95는 v4(실행 245)와 같은 SQL
UPDATE app.eval_result SET error_type = 'JOIN_AGGREGATION' WHERE run_id = 296 AND question_id IN ('Q81', 'Q82', 'Q95', 'Q99');

-- DATE_RANGE: Q93은 v4와 같은 SQL(연도 2023)
UPDATE app.eval_result SET error_type = 'DATE_RANGE' WHERE run_id = 296 AND question_id IN ('Q93');
