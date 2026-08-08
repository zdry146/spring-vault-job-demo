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
├── pom.xml                                 # Spring Boot 3.3.5 + spring-vault-core 3.1.x, Java 21
├── docker-compose.yml                      # Vault (dev mode) + Postgres 16 for local testing
├── src/main/java/com/example/vaultjob/
│   ├── DemoApplication.java                # CLI entry; uses SpringApplication.exit()
│   ├── config/
│   │   ├── VaultProperties.java            # @ConfigurationProperties("vault")
│   │   ├── VaultConfig.java                # VaultTemplate bean + AppRole auth wiring
│   │   └── DataSourceConfig.java           # HikariDataSource built from Vault creds
│   ├── credentials/
│   │   ├── DbCredentials.java              # record (user, pass, leaseId, ttl)
│   │   └── VaultCredentialProvider.java    # read/renew/revoke leases; tracks outstanding ones
│   ├── job/
│   │   └── BatchJobRunner.java             # CommandLineRunner; sample SELECT
│   └── revoke/
│       └── LeaseRevokingShutdownHook.java  # @PreDestroy + optional JVM hook
├── src/test/java/...                       # Mockito unit tests (no real Vault needed)
├── scripts/
│   ├── vault-setup.sh                      # idempotent one-shot Vault admin setup
│   └── wrap-secret-id.sh                   # re-fetch secret-id; writes to file with mode 600
└── docs/
    └── lifecycle.md                        # short-job vs long-job rotation pattern
```

---

## Prerequisites

- Java 21 (project compiles to 21 bytecode)
- Maven 3.9+
- Docker (for local Vault + Postgres via `docker-compose.yml`)
- `vault` CLI on PATH (`brew install vault` / `apt install vault`)
- `jq` for the setup script

---

## Quick start (local dev)

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

Coverage:

- `VaultCredentialProviderTest` — happy path, missing fields, multi-lease
  revocation, exception isolation during revocation.
- `BatchJobRunnerTest` — verifies JdbcTemplate is invoked with the Vault creds.

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