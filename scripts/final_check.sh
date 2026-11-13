#!/usr/bin/env bash
# 동결 전 전체 점검. 하나라도 실패하면 0이 아닌 값으로 끝난다. DB, Ollama, .env가 필요하다.
set -euo pipefail

cd "$(dirname "$0")/.."
set -a; . ./.env; set +a

step() { echo; echo "== $1"; }

step ".env가 git에 추적되지 않는다"
[ "$(git ls-files .env | wc -l | tr -d ' ')" = "0" ]

step "추적 파일에 실제 비밀번호가 없다"
for secret in "$POSTGRES_PASSWORD" "$SCM_RO_PASSWORD"; do
  # 자리표시자나 짧은 값은 다른 문자열의 일부로 걸릴 수 있어 검사 대상에서 뺀다.
  if [ "${#secret}" -lt 12 ]; then
    echo "skipped a password shorter than 12 characters (placeholder?)"
    continue
  fi
  if git grep -qF -- "$secret"; then
    echo "a real password from .env is committed" >&2
    exit 1
  fi
done

step "골든셋 동결 해시"
sha256sum -c eval/golden_set.sha256 eval/golden_set_deep.sha256 eval/golden_set_v3.sha256 eval/golden_set_v4.sha256

step "평가 결과를 내보낸 뒤 DB 초기화"
bash scripts/eval_dump.sh
bash scripts/db_reset.sh

step "시드 검증과 체크섬"
bash scripts/verify_db.sh
bash scripts/db_checksum.sh | diff - db/verify/checksum.expected

step "평가 결과 복원"
bash scripts/eval_restore.sh

step "백엔드 테스트"
(cd backend && ./gradlew clean test integrationTest --console=plain)

step "프런트 빌드"
(cd frontend && npm ci && npx tsc -b && npm run lint && npm run build)

echo
echo "FINAL_CHECK_OK"
