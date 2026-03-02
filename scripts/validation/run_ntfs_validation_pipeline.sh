#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
ANDROID_SCRIPT="$ROOT_DIR/scripts/validation/run_android_ntfs_validation.sh"
POWERSHELL_SCRIPT="$ROOT_DIR/scripts/validation/Invoke-NtfsChkdskValidation.ps1"
OUT_DIR="$ROOT_DIR/out/ntfs-validation/$(date -u +%Y%m%dT%H%M%SZ)"
PASSWORD="Validation-Password-Change-Me"
CONTAINER_NAME="ntfs_validation_container.hc"
RUN_CHKDSK=auto

usage() {
  cat <<'EOF'
Usage: run_ntfs_validation_pipeline.sh [options]

Runs Android NTFS stress validation and optionally runs Windows CHKDSK validation.

Options:
  --password VALUE              Password passed to Android and CHKDSK stage
  --container-name VALUE        Container artifact filename
  --out-dir PATH                Output directory
  --run-chkdsk auto|yes|no      Whether to run PowerShell CHKDSK stage
  --help                        Show help

All unrecognized arguments are forwarded to run_android_ntfs_validation.sh.
EOF
}

ANDROID_ARGS=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    --password)
      PASSWORD="$2"
      ANDROID_ARGS+=("$1" "$2")
      shift 2
      ;;
    --container-name)
      CONTAINER_NAME="$2"
      ANDROID_ARGS+=("$1" "$2")
      shift 2
      ;;
    --out-dir)
      OUT_DIR="$2"
      shift 2
      ;;
    --run-chkdsk)
      RUN_CHKDSK="$2"
      shift 2
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    *)
      ANDROID_ARGS+=("$1")
      shift
      ;;
  esac
done

mkdir -p "$OUT_DIR"

"$ANDROID_SCRIPT" --out-dir "$OUT_DIR" "${ANDROID_ARGS[@]}"

CONTAINER_PATH="$OUT_DIR/$CONTAINER_NAME"
CHKDSK_JSON="$OUT_DIR/chkdsk_result.json"

run_chkdsk_stage() {
  local pwsh_cmd="$1"
  local ps_script="$2"
  local ps_container="$3"
  local ps_out="$4"
  "$pwsh_cmd" -NoProfile -ExecutionPolicy Bypass -File "$ps_script" \
    -ContainerPath "$ps_container" \
    -Password "$PASSWORD" \
    -OutputJson "$ps_out"
}

if [[ "$RUN_CHKDSK" == "no" ]]; then
  echo "CHKDSK stage disabled (--run-chkdsk no)."
  exit 0
fi

if command -v pwsh >/dev/null 2>&1; then
  run_chkdsk_stage "pwsh" "$POWERSHELL_SCRIPT" "$CONTAINER_PATH" "$CHKDSK_JSON"
  exit 0
fi

if command -v pwsh.exe >/dev/null 2>&1 && command -v wslpath >/dev/null 2>&1; then
  run_chkdsk_stage "pwsh.exe" "$(wslpath -w "$POWERSHELL_SCRIPT")" "$(wslpath -w "$CONTAINER_PATH")" "$(wslpath -w "$CHKDSK_JSON")"
  exit 0
fi

if [[ "$RUN_CHKDSK" == "yes" ]]; then
  echo "PowerShell runtime not found but --run-chkdsk yes was requested." >&2
  exit 1
fi

echo "PowerShell not found; skipping CHKDSK stage."
echo "Run manually on Windows:"
echo "  powershell -ExecutionPolicy Bypass -File \"$POWERSHELL_SCRIPT\" -ContainerPath \"$CONTAINER_PATH\" -Password \"$PASSWORD\" -OutputJson \"$CHKDSK_JSON\""
