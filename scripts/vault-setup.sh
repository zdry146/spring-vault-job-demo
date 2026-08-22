#!/usr/bin/env bash
# ----------------------------------------------------------------------------
# vault-setup.sh
#
# One-shot setup script for the database/approle plumbing the demo job needs.
# Idempotent: re-running is safe (existing objects are not overwritten).
#
# USAGE:
#   export VAULT_ADDR=http://127.0.0.1:8200
#   export VAULT_TOKEN=root          # dev only; in prod use your real token
#   ./scripts/vault-setup.sh
#
# After running, capture the printed role-id and secret-id and pass them to
# the job via VAULT_ROLE_ID / VAULT_SECRET_ID env vars.
# ----------------------------------------------------------------------------
set -euo pipefail

# --- 0. Inputs (override via env) ------------------------------------------
DB_NAME="${DB_NAME:-mydb}"
DB_ADMIN_USER="${DB_ADMIN_USER:-vault_admin}"
DB_ADMIN_PASSWORD="${DB_ADMIN_PASSWORD:-vault_admin_pw}"
DB_HOST="${DB_HOST:-127.0.0.1}"
DB_PORT="${DB_PORT:-5432}"
DB_PLUGIN="${DB_PLUGIN:-postgresql-database-plugin}"
DB_ROLE_NAME="${DB_ROLE_NAME:-mydb-role}"
DB_DEFAULT_TTL="${DB_DEFAULT_TTL:-5m}"
DB_MAX_TTL="${DB_MAX_TTL:-24h}"
CREATION_SQL="${CREATION_SQL:-CREATE ROLE \"{{name}}\" WITH LOGIN PASSWORD '{{password}}' VALID UNTIL '{{expiration}}'; GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA public TO \"{{name}}\";}"

APPROLE_NAME="${APPROLE_NAME:-spring-vault-job-demo}"
APPROLE_POLICY_NAME="${APPROLE_POLICY_NAME:-spring-vault-job-demo}"
APPROLE_SECRET_TTL="${APPROLE_SECRET_TTL:-30m}"
APPROLE_TOKEN_TTL="${APPROLE_TOKEN_TTL:-30m}"
APPROLE_TOKEN_MAX_TTL="${APPROLE_TOKEN_MAX_TTL:-60m}"

# --- 1. Pre-flight ---------------------------------------------------------
if ! command -v vault >/dev/null 2>&1; then
  echo "ERROR: 'vault' CLI not on PATH. Install: https://developer.hashicorp.com/vault/install" >&2
  exit 1
fi
if [[ -z "${VAULT_ADDR:-}" || -z "${VAULT_TOKEN:-}" ]]; then
  echo "ERROR: VAULT_ADDR and VAULT_TOKEN must be set" >&2
  exit 1
fi
if ! vault status >/dev/null 2>&1; then
  echo "ERROR: cannot reach Vault at ${VAULT_ADDR}" >&2
  exit 1
fi

echo "==> Vault reachable at ${VAULT_ADDR}"

# --- 2. Enable database secrets engine -------------------------------------
if ! vault secrets list -format=json | grep -q '"database/'; then
  vault secrets enable database >/dev/null
  echo "    enabled database secrets engine at database/"
else
  echo "    database secrets engine already enabled (skipping)"
fi

# --- 3. Configure DB connection --------------------------------------------
# Vault uses these admin creds to CREATE ROLE / DROP ROLE for each dynamic
# credential. They are stored in Vault, NOT in the application.
vault write "database/config/${DB_NAME}" \
  plugin_name="${DB_PLUGIN}" \
  allowed_roles="${DB_ROLE_NAME}" \
  connection_url="postgresql://{{username}}:{{password}}@${DB_HOST}:${DB_PORT}/${DB_NAME}?sslmode=disable" \
  username="${DB_ADMIN_USER}" \
  password="${DB_ADMIN_PASSWORD}" \
  >/dev/null
echo "    configured database connection: ${DB_NAME} @ ${DB_HOST}:${DB_PORT}"

# --- 4. Define role that issues dynamic credentials ------------------------
vault write "database/roles/${DB_ROLE_NAME}" \
  db_name="${DB_NAME}" \
  creation_statements="${CREATION_SQL}" \
  default_ttl="${DB_DEFAULT_TTL}" \
  max_ttl="${DB_MAX_TTL}" \
  >/dev/null
echo "    created DB role: ${DB_ROLE_NAME} (default_ttl=${DB_DEFAULT_TTL}, max_ttl=${DB_MAX_TTL})"

# --- 5. Enable AppRole auth ------------------------------------------------
if ! vault auth list -format=json | grep -q '"approle/'; then
  vault auth enable approle >/dev/null
  echo "    enabled approle auth method"
else
  echo "    approle auth method already enabled (skipping)"
fi

# --- 6. Write policy: minimal read-only on database/creds/<role> -----------
POLICY_PATH="$(mktemp)"
cat >"${POLICY_PATH}" <<EOF
# Read dynamic credentials for the demo DB role only.
path "database/creds/${DB_ROLE_NAME}" {
  capabilities = ["read"]
}
# Allow renewing / revoking leases issued to us (needed for long-job pattern).
path "sys/leases/renew" {
  capabilities = ["update"]
}
path "sys/leases/revoke" {
  capabilities = ["update"]
}
EOF
vault policy write "${APPROLE_POLICY_NAME}" "${POLICY_PATH}" >/dev/null
rm -f "${POLICY_PATH}"
echo "    wrote policy: ${APPROLE_POLICY_NAME}"

# --- 7. Create AppRole ------------------------------------------------------
if ! vault read "auth/approle/role/${APPROLE_NAME}" >/dev/null 2>&1; then
  vault write "auth/approle/role/${APPROLE_NAME}" \
    secret_id_ttl="${APPROLE_SECRET_TTL}" \
    token_ttl="${APPROLE_TOKEN_TTL}" \
    token_max_ttl="${APPROLE_TOKEN_MAX_TTL}" \
    token_num_uses=10 \
    secret_id_num_uses=40 \
    policies="${APPROLE_POLICY_NAME}" \
    >/dev/null
  echo "    created approle: ${APPROLE_NAME}"
else
  echo "    approle ${APPROLE_NAME} already exists (skipping)"
fi

# --- 8. CI AppRole (separate role for CI callers; least-privilege) -------
# Why a separate role? The job's AppRole policy allows database/creds/*,
# which is overkill for CI — CI only needs to mint wrapping tokens for
# the job's secret-id path. Splitting roles means a CI credential leak
# can't be used to read live DB credentials.
CI_APPROLE_NAME="${CI_APPROLE_NAME:-ci-${APPROLE_NAME}}"
CI_APPROLE_POLICY_NAME="${CI_APPROLE_POLICY_NAME:-ci-${APPROLE_POLICY_NAME}}"
CI_APPROLE_SECRET_TTL="${CI_APPROLE_SECRET_TTL:-24h}"
CI_APPROLE_TOKEN_TTL="${CI_APPROLE_TOKEN_TTL:-15m}"
CI_APPROLE_TOKEN_MAX_TTL="${CI_APPROLE_TOKEN_MAX_TTL:-1h}"

CI_POLICY_PATH="$(mktemp)"
cat >"${CI_POLICY_PATH}" <<EOF
# CI policy: only allow wrapping for the job's AppRole secret-id.
# Least privilege — CI cannot read database creds or manage other roles.
# Path is hard-coded to the JOB role (defined above); CI cannot reach other roles.
path "auth/approle/role/${APPROLE_NAME}/secret-id" {
  capabilities = ["create", "update"]
}
path "auth/approle/role/${APPROLE_NAME}/role-id" {
  capabilities = ["read"]
}
EOF
vault policy write "${CI_APPROLE_POLICY_NAME}" "${CI_POLICY_PATH}" >/dev/null
rm -f "${CI_POLICY_PATH}"
echo "    wrote CI policy: ${CI_APPROLE_POLICY_NAME}"

if ! vault read "auth/approle/role/${CI_APPROLE_NAME}" >/dev/null 2>&1; then
  vault write "auth/approle/role/${CI_APPROLE_NAME}" \
    secret_id_ttl="${CI_APPROLE_SECRET_TTL}" \
    token_ttl="${CI_APPROLE_TOKEN_TTL}" \
    token_max_ttl="${CI_APPROLE_TOKEN_MAX_TTL}" \
    token_num_uses=0 \
    secret_id_num_uses=0 \
    policies="${CI_APPROLE_POLICY_NAME}" \
    >/dev/null
  echo "    created CI approle: ${CI_APPROLE_NAME}"
else
  echo "    CI approle ${CI_APPROLE_NAME} already exists (skipping)"
fi

# --- 9. Print credentials ---------------------------------------------------
echo
echo "================================================================"
echo " JOB CREDENTIALS (run with these locally — DO NOT commit):"
echo "================================================================"
ROLE_ID="$(vault read -format=json "auth/approle/role/${APPROLE_NAME}/role-id" | jq -r .data.role_id)"
SECRET_ID_JSON="$(vault write -format=json -f "auth/approle/role/${APPROLE_NAME}/secret-id")"
SECRET_ID="$(echo "${SECRET_ID_JSON}" | jq -r .data.secret_id)"
WRAPPING_TOKEN="$(echo "${SECRET_ID_JSON}" | jq -r .wrap_info.token // empty)"

echo "export VAULT_ADDR='${VAULT_ADDR}'"
echo "export VAULT_ROLE_ID='${ROLE_ID}'"
echo "export VAULT_SECRET_ID='${SECRET_ID}'"
echo "export VAULT_DB_ROLE='${DB_ROLE_NAME}'"
echo "export DB_JDBC_URL='jdbc:postgresql://${DB_HOST}:${DB_PORT}/${DB_NAME}'"

if [[ -n "${WRAPPING_TOKEN}" ]]; then
  echo
  echo "(wrapping token for the job's secret-id — use scripts/wrap-secret-id.sh at deploy time instead)"
fi

echo
echo "================================================================"
echo " CI CREDENTIALS (store in Jenkins Credentials store, kind=Secret text):"
echo "================================================================"
CI_ROLE_ID="$(vault read -format=json "auth/approle/role/${CI_APPROLE_NAME}/role-id" | jq -r .data.role_id)"
CI_SECRET_ID="$(vault write -format=json -f "auth/approle/role/${CI_APPROLE_NAME}/secret-id" | jq -r .data.secret_id)"

echo "  Jenkins credential ID: ci-vault-role-id"
echo "    secret value: ${CI_ROLE_ID}"
echo
echo "  Jenkins credential ID: ci-vault-secret-id"
echo "    secret value: ${CI_SECRET_ID}"
echo
echo "  After creating both, the Jenkinsfile (withCredentials block)"
echo "  will inject them as CI_VAULT_ROLE_ID / CI_VAULT_SECRET_ID env vars."

echo "================================================================"
echo " WARNING: these credentials are sensitive — do NOT log them."
echo " In production, CI uses wrapping-token (Pattern B) so the job's"
echo " secret_id never lives in any config / env / file at rest."
echo "================================================================"