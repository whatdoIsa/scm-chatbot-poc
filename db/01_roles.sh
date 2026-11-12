#!/usr/bin/env bash
set -euo pipefail

: "${SCM_RO_PASSWORD:?SCM_RO_PASSWORD is required}"

# 아래 세 설정은 세션 기본값이라 scm_ro가 SET으로 바꿀 수 있다.
# 쓰기 차단은 04_grants.sql의 권한이, 실행 시간 제한은 ReadOnlySqlExecutor의 setQueryTimeout이 보장한다.
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" -v ro_pw="$SCM_RO_PASSWORD" <<'EOSQL'
CREATE ROLE scm_ro LOGIN PASSWORD :'ro_pw';
ALTER ROLE scm_ro SET search_path = scm;
ALTER ROLE scm_ro SET statement_timeout = '5s';
ALTER ROLE scm_ro SET default_transaction_read_only = on;
EOSQL
