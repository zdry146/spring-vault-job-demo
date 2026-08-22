#!/usr/bin/env bash
# ----------------------------------------------------------------------------
# wrap-secret-id.sh — CI-side wrapping-token injector (Pattern B)
#
# Pattern B K8s deploy flow (production-grade; no static secret_id in cluster):
#
#   ┌──────────┐ 1. AppRole login   ┌──────────┐
#   │  CI      │ ─────────────────▶ │  Vault   │
#   │ (Jenkins)│ ◀───── CI token ───│          │
#   └──────────┘                    └──────────┘
#        │                                │
#        │ 2. write -wrap-ttl=300s        │
#        │    auth/approle/role/<JOB>/secret-id
#        ▼                                ▼
#   ┌──────────┐                    ┌──────────┐
#   │ wrapping │ ──patch───▶ K8s Secret
#   │ token    │            vault-job-credentials
#   └──────────┘              ├ role-id        (long-lived)
#                            └ wrapping-token  (5min TTL, single-use)
#                                       │
#                                       ▼
#                               ┌──────────────┐
#                               │  K8s Job pod │
#                               │  WrappedSecretIdResolver
#                               │    POST /v1/sys/wrapping/unwrap
#                               │  → fresh secret_id
#                               │  → AppRoleAuthentication.login()
#                               │  → Vault client_token
#                               └──────────────┘
#
# Required env (set in Jenkins Credentials + Jenkinsfile `environment`):
#   VAULT_ADDR                 e.g. http://192.168.232.128:8200
#   CI_VAULT_ROLE_ID           CI's own AppRole role-id   (from vault-setup.sh CI section)
#   CI_VAULT_SECRET_ID         CI's own AppRole secret-id (from vault-setup.sh CI section)
#   JOB_APPROLE_NAME           the job's AppRole, e.g. spring-vault-job-demo
#   K8S_NAMESPACE              e.g. vault-demo
#   K8S_SECRET_NAME            e.g. vault-job-credentials
# Optional:
#   WRAP_TTL_SECONDS           default 300 (5 min — single-use, job unwraps at startup)
#   RESTART_JOB                "true" to delete the K8s Job so it gets re-created
#   K8S_CONTEXT                kubectl --context for multi-cluster setups
#   K8S_JOB_NAME               default = JOB_APPROLE_NAME
#   DRY_RUN                    "true" to print actions without touching Vault / K8s
#
# Exit codes:
#   0  success
#   2  bad args / missing required env / missing CLI
#   3  Vault auth / wrap generation failed
#   4  K8s Secret update failed
# ----------------------------------------------------------------------------
set -euo pipefail

# === Config (from env) =====================================================
DRY_RUN="${DRY_RUN:-false}"
WRAP_TTL="${WRAP_TTL_SECONDS:-300}"
RESTART_JOB="${RESTART_JOB:-false}"
K8S_CONTEXT_FLAG=""
[[ -n "${K8S_CONTEXT:-}" ]] && K8S_CONTEXT_FLAG="--context=${K8S_CONTEXT}"
JOB_NAME="${K8S_JOB_NAME:-${JOB_APPROLE_NAME:-}}"
CI_VAULT_TOKEN=""

# === Preflight =============================================================
err() { echo "ERROR: $*" >&2; }
log() { echo "[$(date -Iseconds)] $*"; }
for v in VAULT_ADDR CI_VAULT_ROLE_ID CI_VAULT_SECRET_ID JOB_APPROLE_NAME K8S_NAMESPACE K8S_SECRET_NAME; do
  if [[ -z "${!v:-}" ]]; then err "required env var $v is not set"; exit 2; fi
done
for cmd in vault kubectl jq; do
  if ! command -v "$cmd" >/dev/null 2>&1; then err "$cmd CLI not on PATH"; exit 2; fi
done

# === Step 1: Auth to Vault as CI ===========================================
log "step 1/4: auth CI AppRole → ${VAULT_ADDR}"
if [[ "$DRY_RUN" == "true" ]]; then
  log "  (dry-run) would call: vault write auth/approle/login role_id=*** secret_id=***"
  CI_VAULT_TOKEN="dry-run-token"
else
  CI_VAULT_TOKEN=$(vault write -format=json auth/approle/login \
    role_id="$CI_VAULT_ROLE_ID" \
    secret_id="$CI_VAULT_SECRET_ID" \
    | jq -r '.auth.client_token')
  if [[ -z "$CI_VAULT_TOKEN" || "$CI_VAULT_TOKEN" == "null" ]]; then
    err "CI AppRole login failed (empty token — check CI_VAULT_ROLE_ID/CI_VAULT_SECRET_ID)"
    exit 3
  fi
fi
log "  ✓ CI token acquired (len=${#CI_VAULT_TOKEN})"

# === Step 2: Generate wrapping token for the JOB's AppRole =================
log "step 2/4: generate wrapping token for AppRole '${JOB_APPROLE_NAME}' (TTL=${WRAP_TTL}s)"
if [[ "$DRY_RUN" == "true" ]]; then
  WT="hvs.dryrun-wrapping-token"
  ROLE_ID="dryrun-role-id"
else
  WT=$(VAULT_TOKEN="$CI_VAULT_TOKEN" vault write -format=json \
       -wrap-ttl="${WRAP_TTL}s" -f \
       "auth/approle/role/${JOB_APPROLE_NAME}/secret-id" \
       | jq -r '.wrap_info.token')
  if [[ -z "$WT" || "$WT" == "null" ]]; then
    err "failed to generate wrapping token — check CI policy allows secret-id on ${JOB_APPROLE_NAME}"
    exit 3
  fi
  ROLE_ID=$(VAULT_TOKEN="$CI_VAULT_TOKEN" vault read -format=json \
       "auth/approle/role/${JOB_APPROLE_NAME}/role-id" | jq -r '.data.role_id')
  if [[ -z "$ROLE_ID" || "$ROLE_ID" == "null" ]]; then
    err "failed to read role-id for ${JOB_APPROLE_NAME}"
    exit 3
  fi
fi
log "  ✓ wrapping token (len=${#WT}) + role-id (len=${#ROLE_ID})"

# === Step 3: Update K8s Secret =============================================
log "step 3/4: patch K8s Secret ${K8S_NAMESPACE}/${K8S_SECRET_NAME}"
if [[ "$DRY_RUN" == "true" ]]; then
  log "  (dry-run) would patch secret"
else
  if ! kubectl $K8S_CONTEXT_FLAG -n "$K8S_NAMESPACE" create secret generic "$K8S_SECRET_NAME" \
        --from-literal=role-id="$ROLE_ID" \
        --from-literal=wrapping-token="$WT" \
        --dry-run=client -o yaml \
      | kubectl $K8S_CONTEXT_FLAG -n "$K8S_NAMESPACE" apply -f - >/dev/null; then
    err "failed to update K8s Secret ${K8S_NAMESPACE}/${K8S_SECRET_NAME}"
    exit 4
  fi
fi
log "  ✓ secret updated"

# === Step 4: Optionally restart Job ========================================
if [[ "$RESTART_JOB" == "true" ]]; then
  log "step 4/4: restart Job ${JOB_NAME} in ${K8S_NAMESPACE}"
  if [[ "$DRY_RUN" == "true" ]]; then
    log "  (dry-run) would delete Job ${JOB_NAME}"
  else
    kubectl $K8S_CONTEXT_FLAG -n "$K8S_NAMESPACE" delete job "$JOB_NAME" --ignore-not-found >/dev/null
  fi
  log "  ✓ job restart signalled (will be re-created by kubectl apply / ArgoCD / etc.)"
else
  log "step 4/4: skipped (RESTART_JOB!=true)"
fi

# === Cleanup: revoke CI token (best-effort) ===============================
if [[ "$DRY_RUN" != "true" && -n "$CI_VAULT_TOKEN" ]]; then
  VAULT_TOKEN="$CI_VAULT_TOKEN" vault token revoke -self >/dev/null 2>&1 || true
fi

log "done."
