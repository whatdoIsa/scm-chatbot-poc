#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

result=$(docker compose exec -T db psql -U scm_app -d scm -v ON_ERROR_STOP=1 -At < db/verify/verify.sql)
echo "$result"

if [ -z "$result" ]; then
  echo "verify.sql returned no rows" >&2
  exit 1
fi
if echo "$result" | grep -q '|f$'; then
  echo "verify failed" >&2
  exit 1
fi
