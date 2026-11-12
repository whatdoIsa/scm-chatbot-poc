#!/usr/bin/env bash
set -euo pipefail

: "${SCM_RO_PASSWORD:?SCM_RO_PASSWORD is required}"

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" -v ro_pw="$SCM_RO_PASSWORD" <<'EOSQL'
CREATE ROLE scm_ro LOGIN PASSWORD :'ro_pw';
ALTER ROLE scm_ro SET search_path = scm;
ALTER ROLE scm_ro SET statement_timeout = '5s';
ALTER ROLE scm_ro SET default_transaction_read_only = on;
EOSQL
