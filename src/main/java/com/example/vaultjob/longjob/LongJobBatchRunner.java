package com.example.vaultjob.longjob;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.retry.RetryContext;
import org.springframework.retry.backoff.ExponentialBackOffPolicy;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;

/**
 * Long-running batch entry point. Active only when {@code vault.long-job.enabled=true}.
 *
 * <p>Wraps every DB interaction in a {@link RetryTemplate} configured to
 * retry on {@link SQLException} with exponential backoff. This handles the
 * transient auth errors that occur during a credential rotation: the
 * underlying pool is being swapped under our feet, the next attempt lands
 * on the new (valid) credentials, and the query succeeds.</p>
 *
 * <p>The retry policy is {@link AuthFailureRetryPolicy}, which uses
 * {@link AuthFailureClassifier} to decide retryability. Only credential-
 * rotation-era errors (PG 28P01, MySQL 1045, Oracle ORA-01017, etc.) are
 * retried; everything else (table not found, constraint violation, network
 * down, etc.) propagates immediately as the deterministic failure it is.</p>
 *
 * <p>Implementation note: the JDBC work is delegated to a private method so
 * its {@code throws SQLException} clause lets the checked exception
 * propagate through the {@link org.springframework.retry.support.RetryTemplate}
 * call. A lambda with an explicit throws clause is not legal Java; a method
 * reference is the idiomatic workaround.</p>
 */
@Component
@ConditionalOnProperty(name = "vault.long-job.enabled", havingValue = "true")
public class LongJobBatchRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(LongJobBatchRunner.class);

    private final DataSource dataSource;
    private final RetryTemplate retry;

    public LongJobBatchRunner(LongJobCredentialManager manager) {
        this.dataSource = manager.dataSource();

        // Tighter policy: only retry on classified auth failures
        // (see AuthFailureRetryPolicy + AuthFailureClassifier). Non-auth
        // SQLExceptions (e.g. 42P01 undefined_table) propagate immediately
        // — they are deterministic, retrying them is noise.
        AuthFailureRetryPolicy policy = new AuthFailureRetryPolicy(5);

        ExponentialBackOffPolicy backoff = new ExponentialBackOffPolicy();
        backoff.setInitialInterval(2_000L);
        backoff.setMultiplier(2.0);
        backoff.setMaxInterval(30_000L);

        this.retry = new RetryTemplate();
        this.retry.setRetryPolicy(policy);
        this.retry.setBackOffPolicy(backoff);
    }

    @Override
    public void run(String... args) throws SQLException {
        log.info("=== Long-running batch job started ===");
        // Read VAULT_LONG_JOB_RUN_SECONDS (default 0 = single-shot).
        // > 0 keeps the JVM alive that many seconds so the background renewer
        // (LongJobCredentialManager) can tick at least once and (if the
        // threshold forces it) rotate the credential pair.
        long runSeconds = Long.parseLong(
                System.getenv().getOrDefault("VAULT_LONG_JOB_RUN_SECONDS", "0"));
        long heartbeatSeconds = 15;  // re-query every 15s for fresh audit-trail evidence

        Integer count = retry.execute(this::doQuery);
        log.info("[initial query] tables={}", count);

        if (runSeconds > 0) {
            long deadline = System.currentTimeMillis() + runSeconds * 1000L;
            int iter = 1;
            while (System.currentTimeMillis() < deadline) {
                long remainingMs = deadline - System.currentTimeMillis();
                long sleepMs = Math.min(remainingMs, heartbeatSeconds * 1000L);
                log.info("[heartbeat {}] sleeping {}ms before next query...", iter, sleepMs);
                try {
                    Thread.sleep(sleepMs);
                } catch (InterruptedException ie) {
                    // Restore interrupt flag and abort the loop — JVM is being torn down.
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(
                            "Long-job interrupted while waiting for next heartbeat", ie);
                }
                count = retry.execute(this::doQuery);
                log.info("[heartbeat {}] query OK, tables={}", iter, count);
                iter++;
            }
        }

        log.info("=== Long-running batch job finished cleanly (tables={}) ===", count);
    }

    /**
     * The actual DB work. Method reference target for {@link RetryTemplate#execute}.
     * Declaring {@code throws SQLException} here is what allows the checked
     * exception to flow up to Spring Retry's classifier unchanged.
     */
    private Integer doQuery(RetryContext ctx) throws SQLException {
        log.info("[attempt {}] Querying DB via rotating DataSource", ctx.getRetryCount() + 1);
        try (Connection c = dataSource.getConnection();
             Statement stmt = c.createStatement();
             var rs = stmt.executeQuery(
                     "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'public'")) {
            rs.next();
            return rs.getInt(1);
        }
    }
}