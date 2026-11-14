-- 실행 316(v5, 코드리뷰 반영 후 재측정)의 미분류 오답에 error_type을 적용한다.
-- 문항별 정오, 경로, 생성 SQL, 점검 작동 여부가 첫 측정(실행 296)과 모두 같아 분류도 같다(근거: eval/reports/run-296-v5-error-types.sql).
-- 실행: docker compose exec -T db psql -U scm_app -d scm -f - < eval/reports/run-316-v5-error-types.sql
UPDATE app.eval_result r SET error_type = p.error_type
FROM app.eval_result p
WHERE r.run_id = 316 AND p.run_id = 296 AND p.question_id = r.question_id
  AND NOT r.correct AND r.error_type IS NULL AND p.error_type IS NOT NULL;
