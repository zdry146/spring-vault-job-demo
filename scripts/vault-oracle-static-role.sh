#!/usr/bin/env bash
# ----------------------------------------------------------------------------
# vault-oracle-static-role.sh
#
# Companion to vault-oracle-setup.sh for the **high-frequency** case:
# when the job needs to issue credentials > 10/min, Vault's dynamic
# CREATE USER + DROP USER CASCADE path is too heavy for Oracle (~200ms+
# per revocation, dictionary cache pollution).
#
# Static-role pattern: Vault rotates ONE shared user's password, never
# creates/drops users. Cost per renewal = one ALTER USER statement.
#
# USAGE:
#   export VAULT_ADDR=http://127.0.0.1:8200
#   export VAULT_TOKEN=***
#   export ORACLE_HOST=oracle-prod.local
#   export ORACLE_PORT=1521
#   export ORACLE_SERVICE=ORCLPDB1
#   export ORACLE_USER=vault_admin
#   export ORACLE_PASSWORD=***
#   export APP_DB_USER=app_shared_user     # the Oracle user Vault will rotate
#   ./scripts/vault-oracle-static-role.sh
#
# Prerequisite: APP_DB_USER must already exist in Oracle (this script does
# NOT create it — see scripts/oracle-prereq.sql for the pattern; add a
# CREATE USER app_shared_user ... statement there for app_shared_user).
# ----------------------------------------------------------------------------
set -euo pipefail

# --- 0. Inputs -------------------------------------------------------------
DB_NAME="${DB_NAME:-oracle-prod}"
DB_ADMIN_USER="${DB_ADMIN_USER:-vault_admin}"
DB_ADMIN_PASSWORD="${DB_ADMIN_PASSWORD:-}"
DB_HOST="${DB_HOST:-oracle-prod.local}"
DB_PORT="${DB_PORT:-1521}"
DB_SERVICE="${DB_SERVICE:-ORCLPDB1}"

# The pre-existing Oracle user whose password Vault will rotate.
# MUST already exist; must NOT be SYS / SYSTEM / vault_admin.
APP_DB_USER="${APP_DB_USER:-app_shared_user}"

# Rotation cadence — the only dial that matters for static-roles.
# Lower = tighter password-leak window, higher = fewer ALTER USER per day.
ROTATION_PERIOD="${ROTATION_PERIOD:-24h}"

# Vault-side role path for the static-role. Convention: same as the
# underlying Oracle user with a "-static" suffix so it's obvious it's a
# static-role mapping, not a dynamic one.
STATIC_ROLE_NAME="${STATIC_ROLE_NAME:-${APP_DB_USER}-static}"

# AppRole knobs (separate from the dynamic-role approle in vault-oracle-setup.sh,
# because the policy cap differs — no need for sys/leases/renew at high rate).
APPROLE_NAME="${APPROLE_NAME:-spring-vault-job-demo-oracle-static}"
APPROLE_POLICY_NAME="${APPROLE_POLICY_NAME:-spring-vault-job-demo-oracle-static}"
APPROLE_SECRET_TTL="${APPROLE_SECRET_TTL:-30m}"
APPROLE_TOKEN_TTL="${APPROLE_TOKEN_TTL:-30m}"
APPROLE_TOKEN_MAX_TTL="${APPROLE_TOKEN_MAX_TTL:-1h}"

# --- 1. Pre-flight ---------------------------------------------------------
if ! command -v vault >/dev/null 2>&1; then
  echo "ERROR: 'vault' CLI not on PATH." >&2
  exit 1
fi
if [[ -z "${VAULT_ADDR:-}" || -z "${VAULT_TOKEN:-}" ]]; then
  echo "ERROR: VAULT_ADDR and VAULT_TOKEN must be set" >&2
  exit 1
fi
if [[ -z "${DB_ADMIN_PASSWORD}" ]]; then
  echo "ERROR: DB_ADMIN_PASSWORD must be set (vault_admin password)" >&2
  exit 1
fi
if [[ "${APP_DB_USER}" == "SYS" || "${APP_DB_USER}" == "SYSTEM" || "${APP_DB_USER}" == "VAULT_ADMIN" ]]; then
  echo "ERROR: APP_DB_USER must NOT be SYS / SYSTEM / VAULT_ADMIN (security)" >&2
  exit 1
fi
if ! vault status >/dev/null 2>&1; then
  echo "ERROR: cannot reach Vault at ${VAULT_ADDR}" >&2
  exit 1
fi

echo "==> Vault reachable at ${VAULT_ADDR}"
echo "    Target Oracle user (to be rotated): ${APP_DB_USER}"
echo "    Rotation period: ${ROTATION_PERIOD}"

# --- 2. Reuse the database/config/<DB_NAME> connection ---------------------
# If vault-oracle-setup.sh was run earlier, this config block already exists
# and we'll just point the static-role at it. If not, create it.
if ! vault read "database/config/${DB_NAME}" >/dev/null 2>&1; then
  echo "    database/config/${DB_NAME} not found — creating from scratch."
  vault write "database/config/${DB_NAME}" \
    plugin_name=oracle-database-plugin \
    allowed_roles="${STATIC_ROLE_NAME}" \
    connection_url="jdbc:oracle:thin:@//${DB_HOST}:${DB_PORT}/${DB_SERVICE}" \
    username="${DB_ADMIN_USER}" \
    password="${DB_ADMIN_PASSWORD}" \
    verify_connection=true \
    connection_timeout=60 \
    >/dev/null
  echo "    created database/config/${DB_NAME}"
else
  # Append the new role name to allowed_roles if not already there.
  CURRENT_ALLOWED=$(vault read -format=json "database/config/${DB_NAME}" \
                    | jq -r '.data.allowed_roles // [] | join(",")')
  if [[ ",${CURRENT_ALLOWED}," != *",${STATIC_ROLE_NAME},"* ]]; then
    vault write "database/config/${DB_NAME}/allowed_roles/${STATIC_ROLE_NAME}" \
      allowed_roles="${CURRENT_ALLOWED},${STATIC_ROLE_NAME}" >/dev/null
    echo "    appended ${STATIC_ROLE_NAME} to existing database/config/${DB_NAME}.allowed_roles"
  else
    echo "    database/config/${DB_NAME} already allows ${STATIC_ROLE_NAME}"
  fi
fi

# --- 3. Create the static-role ---------------------------------------------
# rotation_statements: the password change Vault issues on each rotation.
#   We pass {{password}} from Vault into Oracle's ALTER USER.
# revocation_statements: Vault does NOT support revocation_statements on
#   static-roles (the user isn't created/destroyed by Vault). Leave unset.
#
# Note: there's NO creation_statements for a static-role — that would imply
# Vault creates the user, which is the dynamic pattern, not this one.
# Pre-extract the rotation statement to keep the vault write call readable;
# nested \"...\" + line-continuation has bitten this script before (bash
# sometimes reports the parse error at the wrong line).
ROTATION_STMT="ALTER USER \"${APP_DB_USER}\" IDENTIFIED BY \"{{password}}\""
if vault read "database/static-roles/${STATIC_ROLE_NAME}" >/dev/null 2>&1; then
  echo "    static-role ${STATIC_ROLE_NAME} already exists [skipping]"
else
  vault write "database/static-roles/${STATIC_ROLE_NAME}" \
    db_name="${DB_NAME}" \
    username="${APP_DB_USER}" \
    rotation_period="${ROTATION_PERIOD}" \
    rotation_statements="${ROTATION_STMT}" \
    >/dev/null
  echo "    created static-role: ${STATIC_ROLE_NAME} (rotates ${APP_DB_USER} every ${ROTATION_PERIOD})"
fi

# --- 4. AppRole + policy ---------------------------------------------------
if ! vault auth list -format=json | grep -q '"approle/'; then
  vault auth enable approle >/dev/null
  echo "    enabled approle auth method"
else
  echo "    approle auth method already enabled (skipping)"
fi

POLICY_PATH="$(mktemp)"
cat >"${POLICY_PATH}" <<EOF
# Read static credentials for ${APP_DB_USER}.
# Note: NO sys/leases/renew / sys/leases/revoke — static-roles don't issue
# leases, they only rotate passwords on a schedule. The job only needs to
# read once at startup, then re-read when rotation happens.
path "database/static-creds/${STATIC_ROLE_NAME}" {
  capabilities = ["read"]
}
EOF
vault policy write "${APPROLE_POLICY_NAME}" "${POLICY_PATH}" >/dev/null
rm -f "${POLICY_PATH}"
echo "    wrote policy: ${APPROLE_POLICY_NAME}"

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
  echo "    approle ${APPROLE_NAME} already exists [skipping]"
fi

# --- 5. Smoke test: read the static credential once ------------------------
echo
echo "================================================================"
echo " Smoke test: read database/static-creds/${STATIC_ROLE_NAME}"
echo "================================================================"
vault read "database/static-creds/${STATIC_ROLE_NAME}"
echo

# --- 6. Manual rotation trigger (for testing only) -------------------------
echo "================================================================"
echo " To force a rotation NOW (test only — production rotates on schedule):"
echo "   vault write -f database/rotate-root/${DB_NAME}             # rotates vault_admin password"
echo "   vault write -f database/rotate-role/${STATIC_ROLE_NAME}    # rotates ${APP_DB_USER} password"
echo

# --- 7. Print AppRole credentials (like vault-oracle-setup.sh) ------------
echo "================================================================"
echo " JOB CREDENTIALS (use these to authenticate the job):"
echo "================================================================"
ROLE_ID="$(vault read -format=json "auth/approle/role/${APPROLE_NAME}/role-id" | jq -r .data.role_id)"
SECRET_ID_JSON="$(vault write -format=json -f "auth/approle/role/${APPROLE_NAME}/secret-id")"
SECRET_ID="$(echo "${SECRET_ID_JSON}" | jq -r .data.secret_id)"

echo "export VAULT_ADDR='${VAULT_ADDR}'"
echo "export VAULT_ROLE_ID='${ROLE_ID}'"
echo "export VAULT_SECRET_ID='${SECRET_ID}'"
echo "export VAULT_DB_STATIC_ROLE='${STATIC_ROLE_NAME}'"   # job uses database/static-creds/<this>
echo "export DB_JDBC_URL='jdbc:oracle:thin:@//${DB_HOST}:${DB_PORT}/${DB_SERVICE}'"

echo
echo "================================================================"
echo " WHEN TO USE THIS (vs vault-oracle-setup.sh):"
echo "================================================================"
echo "  • Job needs to issue Vault credentials > 10/min          → static-role"
echo "  • Multiple jobs share one Oracle schema                  → static-role"
echo "  • Per-tenant identity / strict per-request audit required → dynamic-role"
echo "  • Burst-y, low-frequency issuance (< 5/min)              → dynamic-role is fine"
echo "================================================================"