package com.example.vaultjob.longjob;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.retry.RetryContext;
import org.springframework.retry.backoff.ExponentialBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
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
 * <p>The retry policy is broad ({@code SQLException}) on purpose: over-retry
 * of a non-auth error terminates quickly because the exception is deterministic.
 * If you want a tighter classifier, see {@code AuthFailureClassifier} and
 * wire up a custom {@link org.springframework.retry.policy.RetryPolicy}.</p>
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

        SimpleRetryPolicy policy = new SimpleRetryPolicy(
                5,
                Map.of(SQLException.class, true),
                /* traverseCauses */ true);

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
        Integer count = retry.execute(this::doQuery);
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