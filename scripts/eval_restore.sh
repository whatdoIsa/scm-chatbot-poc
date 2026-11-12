#!/usr/bin/env bash
# eval/reports/eval_data.sql의 평가 결과를 비어 있는 app.eval_run, app.eval_result에 넣는다.
set -euo pipefail

cd "$(dirname "$0")/.."

existing=$(docker compose exec -T db psql -U scm_app -d scm -Atc "select count(*) from app.eval_run")
if [ "$existing" != "0" ]; then
  echo "app.eval_run already has $existing rows; restore only into an empty database" >&2
  exit 1
fi

docker compose exec -T db psql -U scm_app -d scm -v ON_ERROR_STOP=1 -q -f - < eval/reports/eval_data.sql
docker compose exec -T db psql -U scm_app -d scm -v ON_ERROR_STOP=1 -Atq <<'EOSQL'
SELECT setval('app.eval_run_id_seq', (SELECT max(id) FROM app.eval_run));
SELECT setval('app.eval_result_id_seq', (SELECT max(id) FROM app.eval_result));
EOSQL
echo "restored $(docker compose exec -T db psql -U scm_app -d scm -Atc 'select count(*) from app.eval_run') runs"
