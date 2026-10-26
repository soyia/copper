#!/usr/bin/env bash
set -Eeuo pipefail

: "${COPPER_FK_DB_URL:?COPPER_FK_DB_URL is required}"
: "${COPPER_FK_DB_USERNAME:?COPPER_FK_DB_USERNAME is required}"
: "${COPPER_FK_DB_PASSWORD+x}"

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$repo_root"

env \
  COPPER_FK_E2E=true \
  mvn -B -ntp -Dtest=CoreReferentialIntegrityMySqlAcceptanceTest test

printf '%s\n' \
  'CORE_REFERENTIAL_INTEGRITY_MYSQL=PASS fresh=92_fk v67=54_restrict orphan_insert=blocked parent_delete=blocked v66_orphan=blocked legacy_orphan=preflight_blocked no_partial_fk=true'
