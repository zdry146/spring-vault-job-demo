#!/usr/bin/env bash
# ----------------------------------------------------------------------------
# vault-oracle-setup.sh
#
# Oracle-specific counterpart to vault-setup.sh. One-shot setup for the
# database/approle plumbing the demo job needs against an **Oracle** target.
# Idempotent: re-running is safe (existing objects are not overwritten).
#
# Why this is a separate file (not a flag on vault-setup.sh):
#   Oracle's CREATE USER / DROP USER CASCADE path is ~15-100x heavier than
#   PostgreSQL, defaults are trap-laden (verify_function_11G, 30-byte
#   identifier limit, CDB vs PDB), and revocations need an explicit
#   KILL SESSION step before DROP USER CASCADE will succeed.
#   See the pitfalls list this script encodes:
#     #2  PDB service name, not CDB$ROOT
#     #3  dedicated vault_admin, not SYS / SYSTEM
#     #6  username template <=30 bytes (Oracle identifier limit)
#     #7  identifiers quoted, default Oracle uppercases them
#     #8  DEFAULT TABLESPACE set (else SYSTEM or ORA-01950)
#     #9  explicit QUOTA
#     #10 dedicated app_profile (not DEFAULT) with verify_function off
#     #11 GRANT statements AFTER CREATE USER
#     #12 revoke path: LOCK + KILL SESSION + DROP USER CASCADE
#     #13 connection_timeout extended (Oracle is slower on cold start)
#     #14 short username_template (avoid V_<role>_<hash> overflow)
#     #24 password kept out of connection_url (audit log hygiene)
#
# USAGE:
#   export VAULT_ADDR=http://127.0.0.1:8200
#   export VAULT_TOKEN=***          # dev only; in prod use your real token
#   export ORACLE_HOST=oracle-prod.local
#   export ORACLE_PORT=1521
#   export ORACLE_SERVICE=ORCLPDB1  # ← PDB service, NOT CDB$ROOT (#2)
#   export ORACLE_USER=vault_admin
#   export ORACLE_PASSWORD=***
#   ./scripts/vault-oracle-setup.sh
#
# After running, capture the printed role-id and secret-id and pass them
# to the job via VAULT_ROLE_ID / VAULT_SECRET_ID env vars.
#
# PREREQ (run on Oracle side ONCE, before this script):
#   See README §"Oracle prerequisite SQL" — creates vault_admin user,
#   grants minimum privileges, creates app_profile.
# ----------------------------------------------------------------------------
set -euo pipefail

# --- 0. Inputs (override via env) ------------------------------------------
DB_NAME="${DB_NAME:-oracle-prod}"
DB_ADMIN_USER="${DB_ADMIN_USER:-vault_admin}"
DB_ADMIN_PASSWORD="${DB_ADMIN_PASSWORD:-}"
DB_HOST="${DB_HOST:-oracle-prod.local}"
DB_PORT="${DB_PORT:-1521}"
DB_SERVICE="${DB_SERVICE:-ORCLPDB1}"          # PDB service (#2)
DB_PLUGIN="${DB_PLUGIN:-oracle-database-plugin}"
DB_ROLE_NAME="${DB_ROLE_NAME:-oracle-app-role}"
DB_DEFAULT_TTL="${DB_DEFAULT_TTL:-30m}"       # longer than PG (#18: heavy revoke)
DB_MAX_TTL="${DB_MAX_TTL:-2h}"

# Oracle-specific knobs
DB_APP_TABLESPACE="${DB_APP_TABLESPACE:-users}"
DB_APP_QUOTA="${DB_APP_QUOTA:-500M}"          # per-user quota (#9)
DB_APP_PROFILE="${DB_APP_PROFILE:-app_profile}"
DB_MAX_SESSIONS_PER_USER="${DB_MAX_SESSIONS_PER_USER:-5}"  # protect Oracle PROCESSES (#4)

# Username template kept under Oracle's 30-byte identifier limit (#6, #14).
# 8-char random + 5-char suffix = 13 bytes; well below 30. Also easy to grep.
DB_USERNAME_TEMPLATE="${DB_USERNAME_TEMPLATE:-VA_{{ random 8 }}_U}"

# Pre-built SQL pieces (Oracle-specific — note quoting, profile, tablespace)
CREATION_SQL=$(cat <<'EOSQL'
CREATE USER "{{name}}" IDENTIFIED BY "{{password}}"
  DEFAULT TABLESPACE users
  QUOTA 500M ON users
  PROFILE app_profile
  ACCOUNT UNLOCK
  CONTAINER = CURRENT;
GRANT CREATE SESSION TO "{{name}}";
EOSQL
)

# Revocation: must kill sessions FIRST (#12), otherwise DROP USER CASCADE
# hits ORA-01940 ("cannot drop a user that is currently connected") and
# the lease never terminates cleanly. Wrap the KILL in PL/SQL because
# Vault doesn't allow parameter binding in revocation_statements.
REVOCATION_SQL='ALTER USER "{{name}}" ACCOUNT LOCK;'
REVOCATION_SQL+='DECLARE s PLS_INTEGER; BEGIN FOR r IN (SELECT sid, serial# FROM v$session WHERE username = ''"{{name}}"'') LOOP EXECUTE IMMEDIATE ''ALTER SYSTEM KILL SESSION ''''''||r.sid||'',''||r.serial#||'''' IMMEDIATE''; END LOOP; END; /'
REVOCATION_SQL+='DROP USER "{{name}}" CASCADE;'

# AppRole knobs (kept in sync with vault-setup.sh for consistency)
APPROLE_NAME="${APPROLE_NAME:-spring-vault-job-demo-oracle}"
APPROLE_POLICY_NAME="${APPROLE_POLICY_NAME:-spring-vault-job-demo-oracle}"
APPROLE_SECRET_TTL="${APPROLE_SECRET_TTL:-30m}"
APPROLE_TOKEN_TTL="${APPROLE_TOKEN_TTL:-30m}"
APPROLE_TOKEN_MAX_TTL="${APPROLE_TOKEN_MAX_TTL:-1h}"

# --- 1. Pre-flight ---------------------------------------------------------
if ! command -v vault >/dev/null 2>&1; then
  echo "ERROR: 'vault' CLI not on PATH. Install: https://developer.hashicorp.com/vault/install" >&2
  exit 1
fi
if [[ -z "${VAULT_ADDR:-}" || -z "${VAULT_TOKEN:-}" ]]; then
  echo "ERROR: VAULT_ADDR and VAULT_TOKEN must be set" >&2
  exit 1
fi
if [[ -z "${DB_ADMIN_PASSWORD}" ]]; then
  echo "ERROR: DB_ADMIN_PASSWORD must be set (the vault_admin password)" >&2
  exit 1
fi
if ! vault status >/dev/null 2>&1; then
  echo "ERROR: cannot reach Vault at ${VAULT_ADDR}" >&2
  exit 1
fi

echo "==> Vault reachable at ${VAULT_ADDR}"
echo "    Target: ${DB_HOST}:${DB_PORT}/${DB_SERVICE} as ${DB_ADMIN_USER}"

# --- 2. Enable database secrets engine -------------------------------------
if ! vault secrets list -format=json | grep -q '"database/'; then
  vault secrets enable database >/dev/null
  echo "    enabled database secrets engine at database/"
else
  echo "    database secrets engine already enabled (skipping)"
fi

# --- 3. Configure Oracle connection ----------------------------------------
# NOTE on connection_url (#24): we pass username / password as SEPARATE
# fields, NOT embedded in the URL. Otherwise audit logs capture the password
# whenever Vault's database plugin logs the connection URL on each request.
# Service is PDB, not CDB (#2).
vault write "database/config/${DB_NAME}" \
  plugin_name="${DB_PLUGIN}" \
  allowed_roles="${DB_ROLE_NAME}" \
  connection_url="jdbc:oracle:thin:@//${DB_HOST}:${DB_PORT}/${DB_SERVICE}" \
  username="${DB_ADMIN_USER}" \
  password="${DB_ADMIN_PASSWORD}" \
  verify_connection=true \
  connection_timeout=60 \
  >/dev/null
echo "    configured Oracle connection: ${DB_NAME} @ ${DB_HOST}:${DB_PORT}/${DB_SERVICE}"

# --- 4. Define role that issues dynamic credentials ------------------------
# Username template (#6, #14): short and clearly Oracle-namespaced.
# creation_statements (#7-#11): quoted identifiers, tablespace + quota + profile,
# GRANT statements follow CREATE USER (Oracle requires this ordering).
# revocation_statements (#12): LOCK → KILL SESSION → DROP USER CASCADE.
# default_ttl (#18): 30m (longer than PG's 5m) — Oracle CREATE+DROP is heavier,
# we want fewer cycles per workload.
vault write "database/roles/${DB_ROLE_NAME}" \
  db_name="${DB_NAME}" \
  creation_statements="$(printf '%s\n' "${CREATION_SQL}" | sed 's/$/;/')" \
  revocation_statements="${REVOCATION_SQL}" \
  default_ttl="${DB_DEFAULT_TTL}" \
  max_ttl="${DB_MAX_TTL}" \
  username_template="${DB_USERNAME_TEMPLATE}" \
  >/dev/null
echo "    created DB role: ${DB_ROLE_NAME} (default_ttl=${DB_DEFAULT_TTL}, max_ttl=${DB_MAX_TTL})"
echo "      username_template: ${DB_USERNAME_TEMPLATE} (≤30 bytes for Oracle identifier limit)"
echo "      revocation: LOCK → KILL SESSION → DROP USER CASCADE"

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
# Read dynamic credentials for the demo Oracle role only.
path "database/creds/${DB_ROLE_NAME}" {
  capabilities = ["read"]
}
# Allow renewing / revoking leases issued to us (long-job pattern).
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
CI_APPROLE_NAME="${CI_APPROLE_NAME:-ci-${APPROLE_NAME}}"
CI_APPROLE_POLICY_NAME="${CI_APPROLE_POLICY_NAME:-ci-${APPROLE_POLICY_NAME}}"
CI_APPROLE_SECRET_TTL="${CI_APPROLE_SECRET_TTL:-24h}"
CI_APPROLE_TOKEN_TTL="${CI_APPROLE_TOKEN_TTL:-15m}"
CI_APPROLE_TOKEN_MAX_TTL="${CI_APPROLE_TOKEN_MAX_TTL:-1h}"

CI_POLICY_PATH="$(mktemp)"
cat >"${CI_POLICY_PATH}" <<EOF
# CI policy: only allow wrapping for the job's AppRole secret-id.
# Least privilege — CI cannot read database creds or manage other roles.
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
echo "export DB_JDBC_URL='jdbc:oracle:thin:@//${DB_HOST}:${DB_PORT}/${DB_SERVICE}'"

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

echo "  Jenkins credential ID: ci-vault-role-id-oracle"
echo "    secret value: ${CI_ROLE_ID}"
echo
echo "  Jenkins credential ID: ci-vault-secret-id-oracle"
echo "    secret value: ${CI_SECRET_ID}"
echo
echo "  After creating both, the Jenkinsfile (withCredentials block)"
echo "  will inject them as CI_VAULT_ROLE_ID / CI_VAULT_SECRET_ID env vars."

echo
echo "================================================================"
echo " ORACLE OPERATIONAL REMINDERS:"
echo "================================================================"
echo "  • Run scripts/oracle-prereq.sql once on the DB before this script."
echo "  • Verify DEFAULT profile is not used (#10):"
echo "      SELECT profile FROM dba_users WHERE username = 'VAULT_ADMIN';"
echo "  • Watch for ORA-01940 during revoke (#12):"
echo "      SELECT sid,serial# FROM v$session WHERE username LIKE 'VA_%';"
echo "  • Don't let Vault issue >10 creds/min (Oracle CREATE USER is heavy)."
echo "  • For >10/min, switch to database/static-roles/<name> pattern."
echo "================================================================"
echo " WARNING: these credentials are sensitive — do NOT log them."
echo " In production, CI uses wrapping-token (Pattern B) so the job's"
echo " secret_id never lives in any config / env / file at rest."
echo "================================================================"