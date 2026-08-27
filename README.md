# spring-vault-job-demo

Minimal Spring Boot batch job that authenticates to **HashiCorp Vault** with
**AppRole**, fetches dynamic database credentials from the **Database Secrets
Engine**, builds a `DataSource`, runs a small piece of work, and revokes the
lease on shutdown.

> The motivation, alternatives (Spring Cloud Vault vs raw `VaultTemplate`),
> and design choices for VMs vs containers are written up in the conversation
> context. This README is the operator-/engineer-facing guide.

---

## What's in the box

```
.
├── pom.xml                                 # Spring Boot 3.3.5 + spring-vault-core 3.1.x + spring-retry, Java 21
├── docker-compose.yml                      # Vault (dev mode) + Postgres 16 for local testing
├── src/main/java/com/example/vaultjob/
│   ├── DemoApplication.java                # CLI entry; uses SpringApplication.exit()
│   ├── config/
│   │   ├── VaultProperties.java            # @ConfigurationProperties("vault")
│   │   ├── VaultConfig.java                # VaultTemplate bean + AppRole auth wiring
│   │   └── DataSourceConfig.java           # Conditional: short or long-job DataSource bean
│   ├── credentials/
│   │   ├── DbCredentials.java              # record (user, pass, leaseId, ttl)
│   │   └── VaultCredentialProvider.java    # read/renew/revoke leases; tracks outstanding ones
│   ├── job/
│   │   └── BatchJobRunner.java             # CommandLineRunner (short-job mode)
│   ├── longjob/                            # Pattern B (long-job mode)
│   │   ├── DataSourceFactory.java          # Builds HikariDataSource from creds (reusable)
│   │   ├── RotatingDataSource.java         # DelegatingDataSource that swaps target pool
│   │   ├── AuthFailureClassifier.java      # SQLState classifier (PG/MySQL/Oracle/SQL Server)
│   │   ├── AuthFailureRetryPolicy.java     # RetryPolicy: only retry auth failures
│   │   ├── LongJobCredentialManager.java   # @PostConstruct start + renewer scheduler + rotate
│   │   ├── LongJobBatchRunner.java         # AuthFailureRetryPolicy-wrapped CommandLineRunner
│   │   ├── VaultMetrics.java                # Micrometer counters/timers/gauges (long-job only)
│   │   └── VaultHealthIndicator.java       # /actuator/health/vault UP/UNKNOWN/DOWN
│   └── revoke/
│       └── LeaseRevokingShutdownHook.java  # @PreDestroy + optional JVM hook
├── src/test/java/...                       # Mockito unit tests (no real Vault needed); 62 tests
├── Jenkinsfile                              # Pattern B deploy pipeline (Jenkins in-network CI)
├── scripts/
│   ├── vault-setup.sh                      # idempotent one-shot Vault admin setup — PostgreSQL target (job + CI approles)
│   ├── vault-oracle-setup.sh               # Oracle target variant (heavier revocation path, PDB service, app_profile)
│   ├── oracle-prereq.sql                   # one-shot DBA script: vault_admin user + app_profile (run on Oracle before the shell script)
│   └── wrap-secret-id.sh                   # CI-side wrapping-token injector (Pattern B: login CI → wrap secret-id → patch K8s Secret → restart Job)
└── docs/
    ├── lifecycle.md                        # short-job vs long-job rotation pattern (B is implemented)
    └── vault-agent-sidecar.md              # Vault Agent daemon for VMs (alternative to demo's auth flow)
```

---

## Prerequisites

- Java 21 (project compiles to 21 bytecode)
- Maven 3.9+
- Docker (for local Vault + Postgres via `docker-compose.yml`)
- `vault` CLI on PATH (`brew install vault` / `apt install vault`)
- `jq` for the setup script

---

## Two modes: short-job vs long-job

The repo ships both lifecycle patterns (see [`docs/lifecycle.md`](docs/lifecycle.md) for the full
discussion). They are mutually exclusive; flip with one config flag:

| Mode | Config | Active bean | When to use |
|---|---|---|---|
| **Short** (default) | `vault.long-job.enabled=false` | `BatchJobRunner` + `shortJobDataSource` | Job runtime `<` Vault lease duration |
| **Long** | `vault.long-job.enabled=true` | `LongJobBatchRunner` + `LongJobCredentialManager` | Job runtime `≥` Vault lease duration; needs renewal + rotation |

```bash
# Short-job (default)
mvn spring-boot:run

# Long-job
VAULT_LONG_JOB_ENABLED=true mvn spring-boot:run
```

In long-job mode the log will show the manager starting and scheduling renews:

```
INFO  ... LongJobCredentialManager : LongJobCredentialManager started: user=v-approle-..., ttl=300s, renew_interval=150s, threshold=300s
INFO  ... LongJobBatchRunner       : === Long-running batch job started ===
INFO  ... LongJobBatchRunner       : [attempt 1] Querying DB via rotating DataSource
INFO  ... LongJobBatchRunner       : [attempt 1] Public tables visible to dynamic user: N
INFO  ... LongJobBatchRunner       : === Long-running batch job finished cleanly (tables=N) ===
```

If a credential rotation happens mid-job you'll see something like:

```
WARN  ... LongJobCredentialManager : Lease renewal insufficient (renewed=180s, threshold=300s); rotating
INFO  ... LongJobCredentialManager : Rotated DataSource: user=v-approle-new, ttl=600s
INFO  ... LongJobBatchRunner       : [attempt 2] Querying DB via rotating DataSource
```

## Three ways to get `secret_id` into the job

The demo supports three AppRole `secret_id` resolution modes (in priority order):

| Mode | Env vars | When to use |
|---|---|---|
| **1. Wrapping token** (recommended for prod CI/CD) | `VAULT_WRAPPING_TOKEN` or `VAULT_WRAPPING_TOKEN_FILE` | CI/CD issues a wrapping token at deploy time; job unwraps on startup. Keeps `secret_id` out of any config / env / file at rest. |
| **2. File on disk** | `VAULT_SECRET_ID_FILE` | Secret manager (k8s Secret, AWS SSM, Vault Agent) mounts the secret as a file. |
| **3. Env var** (dev only) | `VAULT_SECRET_ID` | Local dev with `vault write -f ... secret-id` piped into `export`. |

For Vault Agent (long-running daemon on the VM), see
[`docs/vault-agent-sidecar.md`](docs/vault-agent-sidecar.md). For wrapped-token
end-to-end CI/CD example, see `scripts/wrap-secret-id.sh`.

```bash
# Mode 1: Wrapping token (production CI/CD)
export VAULT_ROLE_ID=...
export VAULT_WRAPPING_TOKEN=hvs.xxxxx      # from CI/CD deploy step
export VAULT_UNWRAP_OUTPUT_FILE=/run/vault/secret-id   # optional: cache for next run
java -jar my-job.jar

# Mode 2: File (k8s / Vault Agent / SSM)
export VAULT_ROLE_ID=...
export VAULT_SECRET_ID_FILE=/var/run/vault/secret-id
java -jar my-job.jar

# Mode 3: Direct env (dev only)
export VAULT_ROLE_ID=...
export VAULT_SECRET_ID=$(vault write -f auth/approle/role/$ROLE/secret-id | jq -r .data.secret_id)
java -jar my-job.jar
```

---

## Quick start (local dev)

### PostgreSQL path

```bash
# 1. Bring up Vault + Postgres
docker compose up -d
docker compose logs vault | grep "Root Token"   # capture root token

# 2. Configure Vault: enable DBSE, create role + AppRole + policy
export VAULT_ADDR=http://127.0.0.1:8200
export VAULT_TOKEN=<root-token-from-step-1>
./scripts/vault-setup.sh
# ↑ prints VAULT_ROLE_ID and VAULT_SECRET_ID at the end

# 3. Build + run the job
export VAULT_ROLE_ID=...
export VAULT_SECRET_ID=...
export VAULT_DB_ROLE=mydb-role
export DB_JDBC_URL=jdbc:postgresql://127.0.0.1:5432/mydb
mvn spring-boot:run
```

### Oracle path

Oracle is ~15-100× heavier on `CREATE USER` / `DROP USER CASCADE` than PostgreSQL,
so the Oracle variant bakes in all the pitfalls (PDB service, dedicated `vault_admin`,
dedicated `app_profile` with `verify_function` off, KILL SESSION before DROP,
short username template ≤ 30 bytes, etc.). Full list in the script's header.

```bash
# 1. Run the Oracle prerequisite script once, as a DBA (SYS/SYSTEM).
#    Connects to a PDB service (e.g. ORCLPDB1), not CDB$ROOT.
sqlplus sys/<sys-pw>@//oracle-prod.local:1521/ORCLPDB1 as sysdba \
  @scripts/oracle-prereq.sql
# ↑ creates vault_admin + app_profile + sanity checks.
#   Then change the placeholder password on vault_admin:
sqlplus sys/<sys-pw>@//oracle-prod.local:1521/ORCLPDB1 as sysdba
SQL> ALTER USER vault_admin IDENTIFIED BY "real-password" REPLACE ***;

# 2. Configure Vault against the Oracle target
export VAULT_ADDR=http://127.0.0.1:8200
export VAULT_TOKEN=<root-token>
export ORACLE_HOST=oracle-prod.local
export ORACLE_PORT=1521
export ORACLE_SERVICE=ORCLPDB1              # ← PDB service, not CDB
export ORACLE_USER=vault_admin
export ORACLE_PASSWORD=***        # the password you set in step 1
./scripts/vault-oracle-setup.sh
# ↑ prints VAULT_ROLE_ID and VAULT_SECRET_ID at the end, plus an
#   "ORACLE OPERATIONAL REMINDERS" block

# 3. Build + run the job — DB_JDBC_URL points at Oracle instead
export VAULT_ROLE_ID=...
export VAULT_SECRET_ID=...
export VAULT_DB_ROLE=oracle-app-role
export DB_JDBC_URL='jdbc:oracle:thin:@//oracle-prod.local:1521/ORCLPDB1'
mvn spring-boot:run
```

> **Don't issue creds at >10/min** on Oracle: `DROP USER CASCADE` is 200ms–seconds
> and triggers dictionary-cache work. For high-frequency issuance, switch to
> Vault's `database/static-roles/<name>` (single user, password rotation only)
> — see `vault-oracle-setup.sh`'s header for the recommended split.

### Oracle troubleshooting

Most-common failures when first running the Oracle path, in the order you'll usually hit them:

| Symptom | Root cause | Fix |
|---|---|---|
| `ORA-65096: invalid common user or role name` | Connected to **CDB$ROOT**, not a PDB | Reconnect using the **PDB service name** (e.g. `ORCLPDB1`), not the SID. |
| `ORA-01950: no privileges on tablespace 'SYSTEM'` | Dynamic users default to SYSTEM tablespace; `vault_admin` lacks QUOTA | `ALTER USER vault_admin QUOTA UNLIMITED ON users;` (already in `oracle-prereq.sql`). |
| `ORA-01017: invalid username/password` (from app) | Vault's random password rejected by `verify_function_11G` (DEFAULT profile) | Re-run `oracle-prereq.sql` — it creates `app_profile` with `PASSWORD_VERIFY_FUNCTION = NULL`. |
| `ORA-01940: cannot drop a user that is currently connected` | Revocation fires while a session is still open | The script's `revocation_statements` already does `LOCK → KILL SESSION → DROP CASCADE`. If you hit it, the lease will retry on the next sweep; manually clean with the SQL below. |
| `ORA-00972: identifier is too long` | Vault's default username pattern (`V_<role>_<hash>`) > 30 bytes | Override `DB_USERNAME_TEMPLATE` (default `VA_{{ random 8 }}_U` is 13 bytes). |
| `ORA-28000: the account is locked` | Oracle's `FAILED_LOGIN_ATTEMPTS=10` tripped during an auth-failure retry storm | `app_profile` sets it to `UNLIMITED`. Verify: `SELECT limit FROM dba_profiles WHERE profile='APP_PROFILE' AND resource_name='FAILED_LOGIN_ATTEMPTS';` |
| Vault: `500 Internal Server Error` on `database/creds/...` | Mostly: ① connection URL wrong (CDB root, bad service), ② `vault_admin` password wrong, ③ network ACL | `vault read database/config/<name>` returns the error in plain text. Fix the connection first; re-run `vault-oracle-setup.sh`. |
| Vault: `permission denied` on `database/creds/<role>` | `vault_policy_read` not attached to the AppRole | `vault read auth/approle/role/<name>/policies` — must contain `spring-vault-job-demo-oracle`. |
| Leases piling up in Oracle (`dba_users` count climbing) | TTL too short vs job rate, or revocation failing | See pitfall #18: bump `DB_DEFAULT_TTL` to 30m+ or switch to static-roles for > 10/min. |
| After rotating vault_admin password, dynamic creds stop | Vault cached the old password in `database/config/<name>` | `vault write database/config/<name> password=<new-pw>` to refresh, or `vault write -f database/rotate-root/<name>` does this for you. |

**Manual cleanup** for stuck sessions / leaked users:

```sql
-- Find dynamic users and kill any lingering sessions
SELECT 'ALTER SYSTEM KILL SESSION ''' || sid || ',' || serial# || ''' IMMEDIATE;'
  FROM v$session WHERE username LIKE 'VA_%';
-- Then
SELECT 'DROP USER ' || username || ' CASCADE;' FROM dba_users WHERE username LIKE 'VA_%';
```

### Static-role path (high-frequency Oracle)

If the job needs to fetch a credential **more than ~10/min**, or multiple jobs
share one Oracle schema, use `scripts/vault-oracle-static-role.sh`. Vault
rotates one pre-existing user's password instead of CREATE+DROP:

```bash
export APP_DB_USER=app_shared_user          # MUST exist; add a CREATE USER to oracle-prereq.sql for it
export ROTATION_PERIOD=24h                  # password-leak window
./scripts/vault-oracle-static-role.sh
```

The job then authenticates to Vault exactly the same way (AppRole), but
reads `database/static-creds/<name>` instead of `database/creds/<name>`.
No lease — Vault just rotates the password on `rotation_period`; the job
re-reads when it sees the old password fail auth (covered by
`AuthFailureClassifier` in the long-job mode).

| | Dynamic (default) | Static (high-frequency) |
|---|---|---|
| Oracle cost per issuance | CREATE USER + GRANT (20-80ms) + DROP CASCADE on revoke (200ms+) | One `ALTER USER` on rotation schedule |
| Identity per request | Unique `VA_xxxxxxxxxx_U` user | Always the same `app_shared_user` |
| Audit clarity | Per-user trails in `SYS.AUD$` | Single user, sessions distinguished by `v$session.username` only |
| Lease needed | Yes (`sys/leases/renew`, `sys/leases/revoke`) | No — rotation is time-based, not lease-based |
| Best for | Per-tenant isolation, bursty low rate | Shared schema, high rate, simple audit |

Expected log tail:

```
INFO  ... VaultCredentialProvider  : Fetched credentials from Vault path=database/creds/mydb-role lease_ttl=300s
INFO  ... DataSourceConfig         : Acquired dynamic DB credentials from Vault (user=v-approle-..., lease_ttl=300s)
INFO  ... BatchJobRunner           : === Batch job started ===
INFO  ... BatchJobRunner           : Active credential: user=v-approle-..., ttl=300s, lease=database/creds/mydb-role/...
INFO  ... BatchJobRunner           : Public tables visible to dynamic user: N
INFO  ... BatchJobRunner           : === Batch job finished cleanly ===
INFO  ... LeaseRevokingShutdownHook: Revoking outstanding Vault leases on shutdown
INFO  ... VaultCredentialProvider  : Revoked lease database/creds/mydb-role/...
```

---

## Tests

Unit tests only — they mock `VaultTemplate` and run in milliseconds. No
Vault, no Docker, no network.

```bash
mvn test
```

Coverage (62 tests, 9 classes):

| Class | Tests | Covers |
|---|---|---|
| `VaultCredentialProviderTest` | 4 | happy path, missing fields, multi-lease revocation, exception isolation |
| `BatchJobRunnerTest` | 1 | short-job runner with Vault creds |
| `AuthFailureClassifierTest` | 15 | PG/MySQL/Oracle/SQL Server SQLStates, cause chains, edge cases |
| `AuthFailureRetryPolicyTest` | 8 | first-attempt returns true, retry only on auth, max-attempts cap, cause-chain walk |
| `LongJobCredentialManagerTest` | 12 | initial fetch, renew/rotate thresholds, failure isolation, stop semantics, metrics recording |
| `LongJobBatchRunnerTest` | 4 | happy query, retry on auth SQLException, no-retry on non-auth SQLException, no-retry on non-SQLException |
| `VaultMetricsTest` | 5 | counter/timer/gauge registration, success vs failure, idempotent registration |
| `VaultHealthIndicatorTest` | 5 | UNKNOWN at startup, UP recent, DOWN stale, threshold from interval, default 300s |
| `WrappedSecretIdResolverTest` | 8 | unwrap happy path, file write, error responses, wrapping-token-from-file, optional output file |

---

## Observability (long-job mode only)

`VaultMetrics` + `VaultHealthIndicator` are wired only when both
`vault.long-job.enabled=true` AND `spring-boot-starter-actuator` +
`micrometer-registry-prometheus` are on the classpath. Endpoints are
exposed via Spring Boot Actuator; `application.yml` already exposes
`health,prometheus` (override via `MANAGEMENT_ENDPOINTS`).

### Prometheus metrics — `GET /actuator/prometheus`

| Metric | Type | Description |
|---|---|---|
| `vault_lease_renewals_total{result="success"}` | counter | Successful lease renewals |
| `vault_lease_renewals_total{result="failure"}` | counter | Failed renewals (incl. those triggering rotation) |
| `vault_lease_rotations_total` | counter | Times a fresh credential pair was issued |
| `vault_lease_rotation_duration_seconds` | timer (histogram) | End-to-end rotation latency |
| `vault_lease_last_renew_timestamp` | gauge | Epoch ms of last successful renewal (`0` = none yet) |

### Health check — `GET /actuator/health/vault`

| Status | When |
|---|---|
| `UNKNOWN` | Startup grace period — no renewal has happened yet |
| `UP` | Last successful renewal within `threshold` (default `2 × renew interval`; falls back to 300s when interval is auto-computed) |
| `DOWN` | Last successful renewal older than `threshold` (Vault likely unreachable) |

Sample response:

```json
{
  "status": "UP",
  "components": {
    "vault": {
      "status": "UP",
      "details": { "age_seconds": 42, "threshold_seconds": 300 }
    }
  }
}
```

### Design notes

- `LongJobCredentialManager` injects `VaultMetrics` via `ObjectProvider`, so
  the class has no hard dep on the metrics stack — short-job mode
  (no actuator) keeps working.
- `recordMetric()` swallows any metric exception so a misbehaving meter
  registry can never break the renewer scheduler.
- `AuthFailureRetryPolicy` replaces the previous broad `SimpleRetryPolicy`:
  only credential-rotation-era errors (PG 28P01, MySQL 1045, Oracle ORA-01017,
  SQL Server 18456) are retried; everything else propagates immediately.

---

## Production checklist

- [ ] Use **real Vault** (HA, auto-unseal, audit log enabled).
- [ ] **Don't** commit `VAULT_SECRET_ID` to env vars in production; mount it
      from a secret manager via `VAULT_SECRET_ID_FILE`.
- [ ] Choose `DB_DEFAULT_TTL` ≥ 2× worst-case job duration. See
      `docs/lifecycle.md` for the full reasoning.
- [ ] If the job runs longer than `max_ttl`, implement the rotation
      pattern from `docs/lifecycle.md` (Pattern B).
- [ ] Add a Prometheus scrape on lease renewal failures.
- [ ] Out-of-band DB role verification: periodically issue a `SELECT 1`
      with a fresh credential to confirm the role hasn't drifted.

---

## References

- [HashiCorp Vault — Database Secrets Engine](https://developer.hashicorp.com/vault/docs/secrets/databases)
- [HashiCorp Vault — AppRole auth](https://developer.hashicorp.com/vault/docs/auth/approle)
- [Spring Vault reference](https://docs.spring.io/spring-vault/docs/current/reference/html/)
- [`hashicorp/hello-vault-spring`](https://github.com/hashicorp/hello-vault-spring) — official runnable examples
- [`alexandreroman/k8s-vault-dynamic-credentials`](https://github.com/alexandreroman/k8s-vault-dynamic-credentials) — full K8s demo; the Vault setup is identical for a VM

---

## License

MIT (placeholder — pick whatever fits your org before publishing).