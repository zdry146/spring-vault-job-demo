# K8s deployment for spring-vault-job-demo

End-to-end: K8s pod → Vault AppRole (wrapping-token pattern) → dynamic Postgres credentials → batch query → lease revoke on shutdown.

## One-time setup (host)

```bash
# 1. Vault dev server (auto-unseal, in-memory)
nohup vault server -dev -dev-root-token-id=root -dev-listen-address=0.0.0.0:8200 \
    > ~/.vault-logs/vault.log 2>&1 &

# 2. Postgres — needs an admin role for Vault DB engine
#    (or reuse postgres superuser for dev)
PGPASSWORD=postgres psql -h localhost -U postgres -d testdb -c \
    "CREATE ROLE vault_admin WITH LOGIN PASSWORD 'vault_admin_pw' CREATEROLE;"

# 3. Configure Vault (idempotent)
export VAULT_ADDR=http://127.0.0.1:8200
export VAULT_TOKEN=root
export DB_NAME=testdb DB_HOST=127.0.0.1 DB_ADMIN_USER=vault_admin DB_ADMIN_PASSWORD=vault_admin_pw
./scripts/vault-setup.sh
```

## Build the image (host, with K8s using Docker runtime)

```bash
mvn -B -DskipTests package
docker build -t spring-vault-job-demo:0.1.0 .
# K8s via cri-dockerd sees host's `docker images` directly — imagePullPolicy: Never
```

## Deploy to K8s

```bash
# 1. Generate fresh wrapping token (5 min TTL — must be near deploy time)
WT=$(vault write -format=json -wrap-ttl=300s -f auth/approle/role/spring-vault-job-demo/secret-id \
     | jq -r .wrap_info.token)
ROLE_ID=$(vault read -format=json auth/approle/role/spring-vault-job-demo/role-id | jq -r .data.role_id)

# 2. Patch Secret with fresh values
kubectl create namespace vault-demo
kubectl -n vault-demo create secret generic vault-job-credentials \
    --from-literal=role-id="$ROLE_ID" \
    --from-literal=wrapping-token="$WT"

# 3. Apply Job
kubectl apply -f deploy/k8s/job.yaml

# 4. Wait + logs
kubectl -n vault-demo wait --for=condition=Complete job/spring-vault-job-demo --timeout=90s
kubectl -n vault-demo logs -l app=spring-vault-job-demo
```

Expected success log lines:

```
WrappedSecretIdResolver : Successfully unwrapped secret_id (len=36) from Vault
DataSourceConfig : Acquired dynamic DB credentials from Vault (user=v-approle-spring-j-..., lease_ttl=300s)
BatchJobRunner : Public tables visible to dynamic user: N
LeaseRevokingShutdownHook : Revoking outstanding Vault leases on shutdown
```

## How the wrapping-token pattern keeps secrets out of K8s

1. At deploy time, CI runs `vault write -wrap-ttl=300s -f .../secret-id` and stores the **wrapping token** (one-shot, 5-min TTL) in K8s Secret.
2. Pod starts → reads `VAULT_WRAPPING_TOKEN` → calls `POST /v1/sys/wrapping/unwrap` → Vault returns a fresh single-use `secret_id`.
3. AppRole login uses that fresh `secret_id` → gets a Vault token → reads `database/creds/spring-job-role` → Postgres dynamic user is created.
4. On JVM shutdown, `LeaseRevokingShutdownHook` revokes the Vault lease → Vault REVOKE + DROP ROLE on Postgres → dynamic user cleaned up.

The wrapping token is single-use and expires in 5 min, so even if it leaks from K8s Secret, it can't be replayed.
