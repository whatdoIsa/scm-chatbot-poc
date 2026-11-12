#!/usr/bin/env bash
# 평가 결과(app.eval_run, app.eval_result)를 eval/reports/eval_data.sql로 내보낸다.
# DB를 초기화해도 측정 결과와 사람이 붙인 error_type을 되살릴 수 있게 한다.
set -euo pipefail

cd "$(dirname "$0")/.."

docker compose exec -T db pg_dump -U scm_app -d scm --data-only --column-inserts \
  -t app.eval_run -t app.eval_result \
  | grep -vE '^(--|SET |SELECT pg_catalog\.set_config|\\(un)?restrict )' \
  | sed '/^$/N;/^\n$/D' > eval/reports/eval_data.sql

echo "dumped $(grep -c '^INSERT INTO app.eval_run ' eval/reports/eval_data.sql) runs," \
  "$(grep -c '^INSERT INTO app.eval_result ' eval/reports/eval_data.sql) results"
