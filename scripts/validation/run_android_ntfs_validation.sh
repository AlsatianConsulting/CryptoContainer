#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
PACKAGE="dev.alsatianconsulting.cryptocontainer"
RUNNER="${PACKAGE}.test/androidx.test.runner.AndroidJUnitRunner"
TEST_CLASS="dev.alsatianconsulting.cryptocontainer.validation.NtfsIntegrityValidationTest"
PASSWORD="Validation-Password-Change-Me"
CONTAINER_NAME="ntfs_validation_container.hc"
REPORT_NAME="ntfs_stress_report.json"
ITERATIONS=160
CONTAINER_SIZE_MB=128
MIN_PAYLOAD_BYTES=4096
MAX_PAYLOAD_BYTES=262144
VERIFY_EVERY=5
OVERWRITE_EVERY=3
DELETE_PERCENT=35
FINAL_VERIFY_SAMPLE=24
SEED=""
SKIP_INSTALL=0
ADB_SERIAL=""
OUT_DIR="$ROOT_DIR/out/ntfs-validation/$(date -u +%Y%m%dT%H%M%SZ)"

usage() {
  cat <<'EOF'
Usage: run_android_ntfs_validation.sh [options]

Runs the Android instrumentation NTFS integrity stress test and pulls artifacts.

Options:
  --password VALUE              VeraCrypt password used for create/open
  --container-name VALUE        Container artifact name (default: ntfs_validation_container.hc)
  --iterations N                Stress loop iterations
  --size-mb N                   Container size in MiB
  --min-payload-bytes N         Minimum payload bytes
  --max-payload-bytes N         Maximum payload bytes
  --verify-every N              Verify cadence during write loop
  --overwrite-every N           Overwrite cadence during write loop
  --delete-percent N            Percent of files to delete after write loop
  --final-verify-sample N       Number of entries sampled in final verify pass
  --seed N                      Optional deterministic seed
  --serial ID                   ADB device serial
  --out-dir PATH                Output directory for logs/artifacts
  --skip-install                Skip Gradle install steps
  --help                        Show this help
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --password) PASSWORD="$2"; shift 2 ;;
    --container-name) CONTAINER_NAME="$2"; shift 2 ;;
    --iterations) ITERATIONS="$2"; shift 2 ;;
    --size-mb) CONTAINER_SIZE_MB="$2"; shift 2 ;;
    --min-payload-bytes) MIN_PAYLOAD_BYTES="$2"; shift 2 ;;
    --max-payload-bytes) MAX_PAYLOAD_BYTES="$2"; shift 2 ;;
    --verify-every) VERIFY_EVERY="$2"; shift 2 ;;
    --overwrite-every) OVERWRITE_EVERY="$2"; shift 2 ;;
    --delete-percent) DELETE_PERCENT="$2"; shift 2 ;;
    --final-verify-sample) FINAL_VERIFY_SAMPLE="$2"; shift 2 ;;
    --seed) SEED="$2"; shift 2 ;;
    --serial) ADB_SERIAL="$2"; shift 2 ;;
    --out-dir) OUT_DIR="$2"; shift 2 ;;
    --skip-install) SKIP_INSTALL=1; shift ;;
    --help|-h) usage; exit 0 ;;
    *) echo "Unknown argument: $1" >&2; usage; exit 1 ;;
  esac
done

ADB=(adb)
if [[ -n "$ADB_SERIAL" ]]; then
  ADB+=(-s "$ADB_SERIAL")
fi

if ! command -v "${ADB[0]}" >/dev/null 2>&1; then
  echo "adb command not found in PATH." >&2
  exit 1
fi

mkdir -p "$OUT_DIR"

pushd "$ROOT_DIR" >/dev/null
if [[ "$SKIP_INSTALL" -ne 1 ]]; then
  ./gradlew :app:installDebug :app:installDebugAndroidTest
fi
popd >/dev/null

"${ADB[@]}" wait-for-device

INSTRUMENT_CMD=(
  shell am instrument -w -r
  -e class "$TEST_CLASS"
  -e vcPassword "$PASSWORD"
  -e containerName "$CONTAINER_NAME"
  -e iterations "$ITERATIONS"
  -e containerSizeMb "$CONTAINER_SIZE_MB"
  -e minPayloadBytes "$MIN_PAYLOAD_BYTES"
  -e maxPayloadBytes "$MAX_PAYLOAD_BYTES"
  -e verifyEvery "$VERIFY_EVERY"
  -e overwriteEvery "$OVERWRITE_EVERY"
  -e deletePercent "$DELETE_PERCENT"
  -e finalVerifySampleSize "$FINAL_VERIFY_SAMPLE"
  "$RUNNER"
)

if [[ -n "$SEED" ]]; then
  INSTRUMENT_CMD+=(-e seed "$SEED")
fi

echo "Running instrumentation test..."
"${ADB[@]}" "${INSTRUMENT_CMD[@]}" | tee "$OUT_DIR/instrumentation.log"

echo "Pulling validation report..."
"${ADB[@]}" exec-out run-as "$PACKAGE" cat "files/validation/$REPORT_NAME" > "$OUT_DIR/$REPORT_NAME"

echo "Pulling container artifact..."
"${ADB[@]}" exec-out run-as "$PACKAGE" cat "files/validation/$CONTAINER_NAME" > "$OUT_DIR/$CONTAINER_NAME"

if [[ -s "$OUT_DIR/$REPORT_NAME" ]]; then
  echo "Report saved: $OUT_DIR/$REPORT_NAME"
else
  echo "Report pull failed or empty: $OUT_DIR/$REPORT_NAME" >&2
  exit 1
fi

if [[ -s "$OUT_DIR/$CONTAINER_NAME" ]]; then
  echo "Container saved: $OUT_DIR/$CONTAINER_NAME"
else
  echo "Container pull failed or empty: $OUT_DIR/$CONTAINER_NAME" >&2
  exit 1
fi

echo "Android NTFS validation complete."
