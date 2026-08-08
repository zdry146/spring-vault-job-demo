# Vault Credentials: Short-Job vs Long-Job Lifecycle

This document contrasts the two patterns you actually have to choose between when
designing a Spring Boot job that reads dynamic database credentials from
HashiCorp Vault. Both patterns are implemented in this repo:

- **Pattern A (short job)** — `src/main/java/com/example/vaultjob/job/BatchJobRunner.java`
- **Pattern B (long job)** — `src/main/java/com/example/vaultjob/longjob/` (5 classes)

Toggle between them with `vault.long-job.enabled` (default `false`).

## TL;DR

| Scenario | Expected runtime | Pattern | Effort | Code |
|---|---|---|---|---|
| **Short job** | `< vault.lease_duration` | Fetch once at startup; revoke on shutdown | Done | `BatchJobRunner` (default) |
| **Long job** | `>= vault.lease_duration` | Renew lease + rotate `DataSource` periodically | Done | `LongJobBatchRunner` + `LongJobCredentialManager` |

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

> **Status: implemented in this repo.** Set `vault.long-job.enabled=true` to
> switch on `LongJobCredentialManager`, `LongJobBatchRunner`,
> `RotatingDataSource`, `DataSourceFactory`, and `AuthFailureClassifier`.

For jobs that run for hours. Requires four additions on top of the demo:

1. A background **renewer** that calls `sys/leases/renew` before TTL expires.
   — `LongJobCredentialManager` schedules at half the lease TTL.
2. A **rotation strategy** for the JDBC pool when the underlying role changes.
   — `RotatingDataSource` swaps target pool atomically; old pool closed after swap.
3. **Retry-on-auth-failure** so a brief window during rotation doesn't kill the job.
   — `LongJobBatchRunner` wraps every query in a `RetryTemplate` (5 attempts,
   exponential backoff 2s → 30s, retries on any `SQLException`).
4. **Failure isolation**: renewer errors must not crash the main job.
   — `tickSafely()` catches all `RuntimeException` from the renew/rotate path;
     `rotate()` is `synchronized` so concurrent ticks can never double-rotate.

### B.1 Sketch (now: implemented code)

```java
@Component
@ConditionalOnProperty(name = "vault.long-job.enabled", havingValue = "true")
public class LongJobCredentialManager {
    // ... fields: provider, factory, scheduler, rotatingDs, activeCreds, ...

    @PostConstruct
    public void start() {
        DbCredentials initial = provider.fetchCredentials();
        rotatingDs = new RotatingDataSource(factory.build(initial));
        activeCreds.set(initial);
        long interval = configuredRenewIntervalSeconds > 0
            ? configuredRenewIntervalSeconds
            : Math.max(30L, initial.leaseDurationSeconds() / 2L);
        scheduler.scheduleAtFixedRate(this::tickSafely, interval, interval, TimeUnit.SECONDS);
    }

    private void tickSafely() {
        if (!started) return;
        try {
            DbCredentials creds = activeCreds.get();
            long renewed = provider.renewLease(creds);
            if (renewed == 0L || renewed <= renewThresholdSeconds) {
                rotate();
            } else {
                log.debug("Lease renewed; new TTL = {}s", renewed);
            }
        } catch (RuntimeException e) {
            log.error("Renew tick failed (will retry next interval)", e);  // failure isolation
        }
    }

    private synchronized void rotate() { /* fetch + build + swap + close old */ }

    @PreDestroy
    public void stop() {
        started = false;
        scheduler.shutdown();
        // ... wait + shutdownNow fallback ...
        // Lease revocation is handled centrally by LeaseRevokingShutdownHook
    }

    public DataSource dataSource() { return rotatingDs; }
}
```

### B.2 Rotation options for Hikari

Hikari does not support swapping the password of an existing pool. Three
practical choices:

| Option | Trade-off | Status |
|---|---|---|
| **Full pool swap** (`HikariDataSource.close()` + new) | Simple; in-flight queries on old connections fail. Tolerable for batch jobs that retry on auth error. | **Chosen** — `RotatingDataSource.rotate()` |
| **Per-connection wrap** (custom `DataSource`) | Most code; preserves in-flight queries. | Not implemented |
| **Evict-only** (`evictConnection(...)`) | Single connection swap; complex to wire to Hikari internals. | Not implemented |

For batch jobs, **full pool swap** is usually fine — Hikari's
`connectionTestQuery` (or `connectionInitSql` on PG) ensures the new pool
validates each new connection before handing it out. The retry policy in
B.3 absorbs the auth error that an in-flight query on a closed connection
might surface.

`RotatingDataSource extends DelegatingDataSource` is the trick that makes
this work: Spring beans that captured it (e.g. `JdbcTemplate` constructed
by Boot's auto-config) keep the same reference, but every call is
forwarded to the current target pool. No need to rewire Spring beans.

### B.3 Retry-on-auth-failure

Implemented in `LongJobBatchRunner`:

```java
SimpleRetryPolicy policy = new SimpleRetryPolicy(
    5, Map.of(SQLException.class, true), /* traverseCauses */ true);
ExponentialBackOffPolicy backoff = new ExponentialBackOffPolicy();
backoff.setInitialInterval(2_000L); backoff.setMultiplier(2.0); backoff.setMaxInterval(30_000L);
RetryTemplate retry = new RetryTemplate();
retry.setRetryPolicy(policy); retry.setBackOffPolicy(backoff);

@Override
public void run(String... args) throws SQLException {
    Integer count = retry.execute(this::doQuery);
}

private Integer doQuery(RetryContext ctx) throws SQLException {
    try (Connection c = dataSource.getConnection(); Statement s = c.createStatement(); ...) { ... }
}
```

> **Updated:** the broad `SimpleRetryPolicy` was replaced by
> `AuthFailureRetryPolicy` (B from this turn's task list) which delegates
> to `AuthFailureClassifier`. Now only credential-rotation-era errors
> are retried; everything else propagates immediately.

`SQLException` with state `28000` / `28P01` (PostgreSQL) / `ORA-01017`
(Oracle) signals credential rotation in progress — not a real failure.
The classifier treats these as expected transient errors.

**Why method reference, not lambda:** `RetryTemplate.execute` has signature
`<T, E extends Throwable> T execute(RetryCallback<T, E>) throws E`. A
lambda body can't declare `throws E`, but a method reference can — the
private `doQuery(RetryContext) throws SQLException` method is what lets
the checked exception flow up to Spring Retry's classifier unchanged.

**Gotcha on `AuthFailureRetryPolicy.canRetry`:** it MUST return `true`
when `context.getLastThrowable() == null`. Spring Retry calls `canRetry`
*before* the first attempt to decide whether to enter the do-while loop.
Returning `false` here causes `handleRetryExhausted` to fire
immediately, wrapping the absent throwable in `RetryException("Exception
in retry", null)` — which is the symptom that initially made the tests
fail. The pattern mirrors `SimpleRetryPolicy`'s `t == null || retryForException(t)`.

### B.4 What this implementation does NOT include

For a production-grade long-job pattern, you'd also want:

- **Out-of-process credential cache** if multiple job instances run in
  parallel and you want a shared revocation point.
- **Graceful kill handling**: if the JVM is SIGKILLed mid-run, Vault will
  revoke on lease expiry anyway — but you lose deterministic timing.
  (Easy add: an extra `Runtime.addShutdownHook()` call alongside
  `@PreDestroy`.)
- **Active revocation when renew-threshold is crossed but rotation fails**:
  today's `rotate()` catches its own failures and keeps the current pool,
  but the lease could be in an unknown state on the Vault side. A future
  improvement would be to log `vault token lookup-self` results.

### B.5 Observability (implemented)

C and D from this turn's task list — already wired into the codebase.

**Metrics (`VaultMetrics`):** Micrometer counters/timers/gauges published
via Spring Boot Actuator + Prometheus. The renewer calls
`recordRenewalSuccess()` / `recordRenewalFailure()` on each tick;
`recordRotation(Duration)` records end-to-end rotation latency.
`LongJobCredentialManager` injects `VaultMetrics` via `ObjectProvider` so
short-job mode (no actuator) keeps working without hard dep.

**Health check (`VaultHealthIndicator`):** UP iff last successful
renewal is within `threshold` (= `2 × renew-interval-seconds`, falls
back to 300s when interval is auto-computed). UNKNOWN during the
startup grace period; DOWN when the lease is stale.

Both beans are `@ConditionalOnProperty(... long-job.enabled=true)` +
`@ConditionalOnBean(MeterRegistry.class)` / `@ConditionalOnBean(VaultMetrics.class)`,
so they're inert in short-job mode and don't interfere with other
actuator configurations.

### B.6 Three ways to feed `secret_id` to the job

The `VaultConfig` resolves the AppRole `secret_id` in this priority order:

1. **`WrappedSecretIdResolver`** — active when `VAULT_WRAPPING_TOKEN` (or
   `VAULT_WRAPPING_TOKEN_FILE`) is set. Calls `POST /v1/sys/wrapping/unwrap`
   on startup to obtain a fresh `secret_id`. Production-grade pattern for
   CI/CD-deployed jobs; the wrapping token itself is short-lived (single-use,
   typically 5-10 min TTL), and the unwrapped `secret_id` then drives
   normal AppRole auth.
2. **File** — `VAULT_SECRET_ID_FILE` path. Most common in k8s (Secret
   mounted as a file) or with Vault Agent (which writes to disk).
3. **Env var** — `VAULT_SECRET_ID`. Dev only; never use in prod because
   env vars end up in logs.

See [`vault-agent-sidecar.md`](vault-agent-sidecar.md) for the Vault Agent
daemon alternative (a separate pattern, not a code path in the demo).

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