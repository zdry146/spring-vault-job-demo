# Vault Credentials: Short-Job vs Long-Job Lifecycle

This document contrasts the two patterns you actually have to choose between when
designing a Spring Boot job that reads dynamic database credentials from
HashiCorp Vault. The codebase demos the **short-job** pattern; this doc shows
how to extend it for long-running jobs.

## TL;DR

| Scenario | Expected runtime | Pattern | Effort |
|---|---|---|---|
| **Short job** | `< vault.lease_duration` | Fetch once at startup; revoke on shutdown | Demo code is enough |
| **Long job** | `>= vault.lease_duration` | Renew lease + rotate `DataSource` periodically | Custom: see below |

The demo's lease is configurable via `DB_DEFAULT_TTL` (default `5m`); for the
short-job pattern set it comfortably above the worst-case job runtime.

---

## Why the distinction matters

Vault issues a **lease** when it hands out dynamic credentials. When the lease
expires, Vault revokes the underlying database role — and any subsequent
connection attempt with those credentials fails with `28P01` (bad password)
on PostgreSQL or `ORA-01017` on Oracle. So:

- **Job finishes before lease expires**: nothing extra to do.
- **Job outlives the lease**: in-pool connections may still work for a bit
  (the role hasn't been revoked mid-query), but the *next* connection
  acquisition will fail. Result: confusing intermittent failures.

---

## Pattern A: Short job (< lease duration)

The default in this repo. Sequence:

```
JVM start
  │
  ├─ Spring context boots
  │   └─ DataSourceConfig → VaultCredentialProvider.fetchCredentials()
  │       (one Vault HTTP call → { username, password, lease_id, ttl })
  │   └─ HikariDataSource created with those creds
  │
  ├─ CommandLineRunner fires → uses the injected JdbcTemplate
  │
  ├─ Job finishes cleanly
  │
  └─ JVM exits
      └─ @PreDestroy → VaultCredentialProvider.revokeAll()
          (one Vault HTTP call per lease → sys/leases/revoke)
```

**Pros**

- Minimal code. ~80 lines including the DataSource config.
- No scheduler, no lease tracking, no concurrent rotation.
- Deterministic: every run starts with a clean DB role.

**Cons**

- If the job hangs longer than `lease_duration`, the connection pool goes
  stale and there's no recovery path.
- A misconfigured TTL that's *shorter* than the job will silently break it.

**Recommended TTL strategy**

```
lease_duration  ≥  2 × worst_observed_job_duration
                  +  safety_margin_for_slow_db (e.g. 5 min)
```

---

## Pattern B: Long job (≥ lease duration)

For jobs that run for hours. Requires four additions on top of the demo:

1. A background **renewer** that calls `sys/leases/renew` before TTL expires.
2. A **rotation strategy** for the JDBC pool when the underlying role changes.
3. **Retry-on-auth-failure** so a brief window during rotation doesn't kill the job.
4. **Failure isolation**: renewer errors must not crash the main job.

### B.1 Sketch

```java
@Component
public class LongJobCredentialManager {

    private static final Logger log = LoggerFactory.getLogger(LongJobCredentialManager.class);

    private final VaultCredentialProvider provider;
    private final DataSourceFactory factory;
    private final long renewBeforeSeconds;
    private final AtomicReference<DataSource> currentDs = new AtomicReference<>();
    private final AtomicReference<DbCredentials> currentCreds = new AtomicReference<>();

    public LongJobCredentialManager(...) {
        // schedule renewer on a single-thread executor at half the lease TTL
        long renewInterval = Math.max(60, currentCreds.get().leaseDurationSeconds() / 2);
        Executors.newSingleThreadScheduledExecutor()
            .scheduleAtFixedRate(this::tick, renewInterval, renewInterval, SECONDS);
    }

    private void tick() {
        DbCredentials creds = currentCreds.get();
        long renewed = provider.renewLease(creds);
        if (renewed == 0 || renewed <= renewBeforeSeconds) {
            log.warn("Lease renewal degraded (got {}s) — rotating DataSource", renewed);
            rotate();
        } else {
            log.debug("Lease renewed; new TTL {}s", renewed);
        }
    }

    private void rotate() {
        DbCredentials fresh = provider.fetchCredentials();
        DataSource next = factory.build(fresh);
        DataSource old = currentDs.getAndSet(next);
        try { if (old instanceof AutoCloseable c) c.close(); } catch (Exception ignored) {}
        currentCreds.set(fresh);
    }

    public DataSource dataSource() { return currentDs.get(); }
}
```

### B.2 Rotation options for Hikari

Hikari does not support swapping the password of an existing pool. Three
practical choices:

| Option | Trade-off |
|---|---|
| **Full pool swap** (`HikariDataSource.close()` + new) | Simple; in-flight queries on old connections fail. Tolerable for batch jobs that retry on auth error. |
| **Per-connection wrap** (custom `DataSource`) | Most code; preserves in-flight queries. |
| **Evict-only** (`evictConnection(...)`) | Single connection swap; complex to wire to Hikari internals. |

For batch jobs, **full pool swap** is usually fine — Hikari's
`connectionTestQuery` (or `connectionInitSql` on PG) ensures the new pool
validates each new connection before handing it out.

### B.3 Retry-on-auth-failure

Wrap the business logic:

```java
RetryTemplate retry = RetryTemplate.builder()
    .maxAttempts(3)
    .exponentialBackoff(2_000, 2.0, 30_000)
    .retryOn(SQLException.class)
    .build();

retry.execute(ctx -> batchJob.runOnce());
```

`SQLException` with state `28000` / `28P01` (PostgreSQL) / `ORA-01017`
(Oracle) signals credential rotation in progress — not a real failure.

### B.4 What this demo doesn't implement

For a complete long-job pattern, you'd also want:

- **Out-of-process credential cache** if multiple job instances run in
  parallel and you want a shared revocation point.
- **Metrics**: lease renewals / rotations / failures exposed to Prometheus.
- **Health check**: `/actuator/health/vault` returning `UP` only if a
  recent renewal succeeded.
- **Graceful kill handling**: if the JVM is SIGKILLed mid-run, Vault will
  revoke on lease expiry anyway — but you lose deterministic timing.

---

## Choosing TTL parameters

Quick rule of thumb for the **demo** scenario:

```hcl
# Vault: database/roles/<role>
default_ttl = "5m"   # ← matches job worst-case; bump for safety
max_ttl     = "1h"   # ← cap on lease duration including renewals
```

For **long jobs**, prefer larger `max_ttl` so renewals don't have to fire
constantly:

```hcl
default_ttl = "1h"
max_ttl     = "24h"
```

The renewer will keep extending toward `max_ttl`; once `max_ttl` is reached
you must rotate (issue a brand-new credential pair from Vault).

---

## Sequence diagrams

### Short job

```
   App         Vault           DB
    │            │              │
    │──auth─────▶│              │
    │◀─token─────│              │
    │──read creds▶              │
    │◀─u/p/lease─┤             │
    │──connect (u/p)──────────▶│
    │   (Hikari pool)          │
    │──SELECT ────────────────▶│
    │◀─rows─────────────────────┤
    │──revoke lease───────────▶│
    │   (on shutdown)           │
```

### Long job with rotation

```
   App         Vault           DB
    │            │              │
    │──read creds▶              │
    │◀─u/p/lease (TTL=1h)─┤   │
    │──connect ──────────────────▶│
    │──work──────────────────────▶│
    │                            │
    │   (45 min later)           │
    │──renew lease▶             │
    │◀─same lease (TTL=1h again)─┤│
    │──work──────────────────────▶│
    │                            │
    │   (45 min later, near TTL) │
    │──renew fails ──────────────▶│  ── OR  ──
    │──read new creds▶           │──read new creds▶
    │──rotate pool ─────────────▶│──connect (new u/p)▶
    │──work (new pool) ──────────▶│──work──▶
```