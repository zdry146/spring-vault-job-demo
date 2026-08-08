#!/usr/bin/env bash
# ----------------------------------------------------------------------------
# wrap-secret-id.sh
#
# Re-fetch a fresh secret-id for the AppRole. By default prints the raw
# value; with --write-file <path>, writes to a file instead so the job can
# read it via VAULT_SECRET_ID_FILE (preferred for production — keeps the
# value out of env vars that might end up in logs).
#
# USAGE:
#   ./scripts/wrap-secret-id.sh                       # print to stdout
#   ./scripts/wrap-secret-id.sh --write-file ./secret-id
# ----------------------------------------------------------------------------
set -euo pipefail

APPROLE_NAME="${APPROLE_NAME:-spring-vault-job-demo}"
OUT_PATH=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --write-file) OUT_PATH="$2"; shift 2 ;;
    -h|--help)
      sed -n '2,12p' "$0"; exit 0 ;;
    *) echo "unknown arg: $1" >&2; exit 2 ;;
  esac
done

if [[ -z "${VAULT_ADDR:-}" || -z "${VAULT_TOKEN:-}" ]]; then
  echo "ERROR: VAULT_ADDR and VAULT_TOKEN must be set" >&2
  exit 1
fi

RAW="$(vault write -format=json -f "auth/approle/role/${APPROLE_NAME}/secret-id")"
SECRET_ID="$(echo "${RAW}" | jq -r .data.secret_id)"

if [[ -z "${OUT_PATH}" ]]; then
  echo "${SECRET_ID}"
else
  umask 077
  echo "${SECRET_ID}" >"${OUT_PATH}"
  echo "wrote secret-id to ${OUT_PATH} (mode 600)" >&2
fi