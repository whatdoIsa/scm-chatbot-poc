#!/usr/bin/env bash
# 평가 결과(app.eval_run, app.eval_result)를 eval/reports/eval_data.sql로 내보낸다.
# DB를 초기화해도 측정 결과와 사람이 붙인 error_type을 되살릴 수 있게 한다.
set -euo pipefail

cd "$(dirname "$0")/.."

target=eval/reports/eval_data.sql
tmp=$(mktemp)
trap 'rm -f "$tmp"' EXIT

# 임시 파일에 먼저 쓴다. 덤프가 실패하거나 비어 있으면 기존 파일을 건드리지 않는다.
docker compose exec -T db pg_dump -U scm_app -d scm --data-only --column-inserts \
  -t app.eval_run -t app.eval_result > "$tmp"

runs=$(grep -c '^INSERT INTO app.eval_run ' "$tmp" || true)
results=$(grep -c '^INSERT INTO app.eval_result ' "$tmp" || true)
if [ "$runs" = "0" ]; then
  echo "the database has no eval runs; $target is left unchanged" >&2
  exit 1
fi

cp "$tmp" "$target"
echo "dumped $runs runs, $results results"
