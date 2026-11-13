-- 실행 245(v4, 새 세트 40문항)의 미분류 오답에 error_type을 적용한다.
-- 자동 분류: Q105 ACTION_MISMATCH(되묻기 문항을 stockout_rate 전체 값으로 답함).
-- 원인이 여러 개인 문항은 기존 실행과 같은 기준으로 하나만 붙였다(지표 문항은 지표 계산, 그 밖에는 먼저 걸리는 것).
-- 실행: docker compose exec -T db psql -U scm_app -d scm -f - < eval/reports/run-245-v4-error-types.sql

-- TOOL_SELECTION: 도구가 답하지 않는 질문을 도구로 보냄.
-- Q88 예측 정확도가 가장 높은 '거점'을 품목 순위로, Q91 최근 7일 수도권 '출고량'(합계)을 일평균으로,
-- Q99 이번 주 '예측 수량'을 예측 정확도로 답함. 세 유형 모두 tools_v4.yml의 not_for에 적혀 있었다
UPDATE app.eval_result SET error_type = 'TOOL_SELECTION' WHERE run_id = 245 AND question_id IN ('Q88', 'Q91', 'Q99');

-- TOOL_ARGUMENT: Q83 "재고보유일수가 가장 긴 품목 3개"를 맞는 도구(days_of_supply ranking, top_n 3)로 보냈지만 order를 asc로 줌
UPDATE app.eval_result SET error_type = 'TOOL_ARGUMENT' WHERE run_id = 245 AND question_id IN ('Q83');

-- JOIN_AGGREGATION: SQL 경로.
-- Q81 재고보유일수의 분모를 거점 구분 없이 SKU 전체 판매로 계산(상관 서브쿼리에 location_id 조건 누락),
-- Q82 일별 행의 qty를 직전 7일 합계와 비교(품목별 두 기간 합계를 비교하지 않음),
-- Q95 어제 가장 많이 팔린 품목의 판매량 대신 어제 전체 판매량을 냄
UPDATE app.eval_result SET error_type = 'JOIN_AGGREGATION' WHERE run_id = 245 AND question_id IN ('Q81', 'Q82', 'Q95');

-- DATE_RANGE: Q93 '9/14~9/20'의 연도를 2023으로 씀
UPDATE app.eval_result SET error_type = 'DATE_RANGE' WHERE run_id = 245 AND question_id IN ('Q93');
