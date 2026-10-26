#!/usr/bin/env bash
set -Eeuo pipefail

# Run against a disposable database only. Never point this runner at copper_v2.
: "${COPPER_RELEASE_DB_URL:?COPPER_RELEASE_DB_URL is required}"
: "${COPPER_RELEASE_DB_USERNAME:?COPPER_RELEASE_DB_USERNAME is required}"
: "${COPPER_RELEASE_DB_PASSWORD+x}"

python3 - "$COPPER_RELEASE_DB_URL" <<'PY'
import re
import sys
from urllib.parse import urlsplit

url = sys.argv[1]
if not url.startswith("jdbc:mysql://"):
    raise SystemExit("Only an explicit JDBC MySQL URL is accepted")
database = urlsplit(url.removeprefix("jdbc:")).path.removeprefix("/")
if not re.fullmatch(r"copper_release_acceptance_[A-Za-z0-9_]+", database):
    raise SystemExit("Use a disposable copper_release_acceptance_* database")
PY

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
runtime="$(mktemp -d "${TMPDIR:-/tmp}/copper-current-release.XXXXXX")"
trap 'rm -rf -- "$runtime"' EXIT
install -d -m 0700 "$runtime/files" "$runtime/exports" "$runtime/tmp" "$runtime/secrets" "$runtime/logs"

export COPPER_DB_URL="$COPPER_RELEASE_DB_URL"
export COPPER_DB_USERNAME="$COPPER_RELEASE_DB_USERNAME"
export COPPER_DB_PASSWORD="$COPPER_RELEASE_DB_PASSWORD"
export COPPER_PORT=8193
export COPPER_TIME_ZONE=Asia/Shanghai
export COPPER_DB_TIME_ZONE_OFFSET=+08:00
export COPPER_FLYWAY_ENABLED=true
export COPPER_FLYWAY_BASELINE_ON_MIGRATE=false
export COPPER_DATA_ROOT="$runtime"
export COPPER_FILE_STORAGE_PATH="$runtime/files"
export COPPER_EXPORT_PATH="$runtime/exports"
export COPPER_UPLOAD_TEMP_PATH="$runtime/tmp"
export COPPER_LOG_FILE="$runtime/logs/backend.log"
export COPPER_LOG_LEVEL=INFO
export COPPER_BOOTSTRAP_USERNAME=admin
export COPPER_BOOTSTRAP_CREDENTIAL_FILE="$runtime/secrets/initial-admin.txt"
export COPPER_ALLOWED_ORIGINS=http://127.0.0.1:18080
export COPPER_COOKIE_SECURE=false
export COPPER_COOKIE_SAME_SITE=Lax
export COPPER_OCR_ENABLED=false

export COPPER_15_CATEGORY_ACCEPTANCE=true
export COPPER_COMPLEX_PRODUCT_ACCEPTANCE=true
export COPPER_DIRTY_CLOSE_ACCEPTANCE=true
export COPPER_ERP02_E2E=true
export COPPER_ERP03_E2E=true
export COPPER_ERP04_E2E=true
export COPPER_ERP05_E2E=true
export COPPER_ERP06_E2E=true
export COPPER_ERP07_E2E=true
export COPPER_ERP08_E2E=true
export COPPER_ERP09_E2E=true
export COPPER_ERP12_E2E=true
export COPPER_ERP15_E2E=true
export COPPER_ERP18_E2E=true
export COPPER_ERP_MONTH_END_E2E=true
export COPPER_DIRECT_TRADE_E2E=true
export COPPER_ERP_LITE_E2E=true
export COPPER_PARTNER_SAFETY_E2E=true
export COPPER_PACKAGING_REF_E2E=true
export COPPER_RELEASE_SECURITY_E2E=true
export COPPER_PACKAGING_REF_DB_URL="$COPPER_RELEASE_DB_URL"
export COPPER_PACKAGING_REF_DB_USERNAME="$COPPER_RELEASE_DB_USERNAME"
export COPPER_PACKAGING_REF_DB_PASSWORD="$COPPER_RELEASE_DB_PASSWORD"

cd "$repo_root"
export COPPER_RELEASE_ACCEPTANCE_STARTED_NS="$(python3 -c 'import time; print(time.time_ns())')"
arguments=(-B -ntp '-Dtest=*MySqlAcceptanceTest,!CoreReferentialIntegrityMySqlAcceptanceTest,!ReleaseRecoveryFixtureMySqlAcceptanceTest')
if [[ -n "${COPPER_MAVEN_ARG_LINE:-}" ]]; then
  arguments+=("-DargLine=$COPPER_MAVEN_ARG_LINE")
fi
mvn "${arguments[@]}" test

python3 - <<'PY'
from pathlib import Path
import os
import re
import xml.etree.ElementTree as ET

reports = []
started = int(os.environ["COPPER_RELEASE_ACCEPTANCE_STARTED_NS"])
for source in Path("src/test/java").rglob("*MySqlAcceptanceTest.java"):
    if source.stem in {"CoreReferentialIntegrityMySqlAcceptanceTest", "ReleaseRecoveryFixtureMySqlAcceptanceTest"}:
        continue
    package = re.search(r"package\s+([\w.]+);", source.read_text()).group(1)
    report = Path("target/surefire-reports") / f"TEST-{package}.{source.stem}.xml"
    if not report.exists() or report.stat().st_mtime_ns < started:
        raise SystemExit(f"Missing current-run real MySQL report: {source.stem}")
    reports.append(report)
totals = dict(tests=0, failures=0, errors=0, skipped=0)
for path in reports:
    suite = ET.parse(path).getroot()
    for key in totals:
        totals[key] += int(suite.get(key, "0"))
if totals["failures"] or totals["errors"] or totals["skipped"]:
    raise SystemExit(f"Real MySQL acceptance incomplete: {totals}")
print(f"CURRENT_RELEASE_MYSQL_ACCEPTANCE=PASS suites={len(reports)} tests={totals['tests']} skipped=0")
PY
