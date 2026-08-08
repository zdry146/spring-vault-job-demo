package com.example.vaultjob.longjob;

import com.example.vaultjob.config.VaultProperties;
import com.example.vaultjob.credentials.DbCredentials;
import com.example.vaultjob.credentials.VaultCredentialProvider;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Long-job credential lifecycle manager.
 *
 * <p>Active when {@code vault.long-job.enabled=true}. Owns:</p>
 * <ol>
 *   <li>The initial credential fetch and pool build (via {@link DataSourceFactory}).</li>
 *   <li>A background renewer on a single-threaded daemon scheduler that calls
 *       {@code sys/leases/renew} at half the lease TTL.</li>
 *   <li>Rotation logic: when a renewal fails or returns a TTL below the
 *       configured threshold, fetch a fresh credential pair, build a new pool,
 *       and atomically swap it into the {@link RotatingDataSource}. The old
 *       pool is closed after the swap.</li>
 *   <li>Graceful shutdown: stops the scheduler before the rest of the context
 *       tears down. Lease revocation is handled by
 *       {@code LeaseRevokingShutdownHook} calling
 *       {@link VaultCredentialProvider#revokeAll()}.</li>
 * </ol>
 *
 * <p><b>Concurrency:</b> {@code rotate()} is {@code synchronized} so concurrent
 * ticks (e.g. due to scheduler overlap) never produce double-rotation. The
 * {@link RotatingDataSource} swap is itself a simple volatile-style write,
 * safe under the {@code synchronized} guard.</p>
 */
@Component
@ConditionalOnProperty(name = "vault.long-job.enabled", havingValue = "true")
public class LongJobCredentialManager {

    private static final Logger log = LoggerFactory.getLogger(LongJobCredentialManager.class);

    private final VaultCredentialProvider provider;
    private final DataSourceFactory factory;
    private final long configuredRenewIntervalSeconds;
    private final long renewThresholdSeconds;

    private final ScheduledExecutorService scheduler;
    private final AtomicReference<DbCredentials> activeCreds = new AtomicReference<>();
    private volatile RotatingDataSource rotatingDs;
    private volatile boolean started;

    public LongJobCredentialManager(VaultCredentialProvider provider,
                                    DataSourceFactory factory,
                                    VaultProperties props) {
        this.provider = provider;
        this.factory = factory;
        this.configuredRenewIntervalSeconds = props.getLongJob().getRenewIntervalSeconds();
        this.renewThresholdSeconds = props.getRenewBeforeExpirySeconds();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "vault-lease-renewer");
            t.setDaemon(true);
            return t;
        });
    }

    @PostConstruct
    public void start() {
        DbCredentials initial = provider.fetchCredentials();
        rotatingDs = new RotatingDataSource(factory.build(initial));
        activeCreds.set(initial);
        started = true;

        long interval = configuredRenewIntervalSeconds > 0
                ? configuredRenewIntervalSeconds
                : Math.max(30L, initial.leaseDurationSeconds() / 2L);
        scheduler.scheduleAtFixedRate(this::tickSafely, interval, interval, TimeUnit.SECONDS);
        log.info("LongJobCredentialManager started: user={}, ttl={}s, renew_interval={}s, threshold={}s",
                initial.username(), initial.leaseDurationSeconds(),
                interval, renewThresholdSeconds);
    }

    /**
     * Visible for tests: simulates one scheduler tick. Production uses the
     * scheduler; tests call this directly to assert behavior deterministically.
     */
    public void tickSafely() {
        if (!started) {
            return;
        }
        try {
            DbCredentials creds = activeCreds.get();
            if (creds == null) {
                return;
            }
            long renewed = provider.renewLease(creds);
            if (renewed == 0L || renewed <= renewThresholdSeconds) {
                log.warn("Lease renewal insufficient (renewed={}s, threshold={}s); rotating",
                        renewed, renewThresholdSeconds);
                rotate();
            } else {
                log.debug("Lease renewed; new TTL = {}s", renewed);
            }
        } catch (RuntimeException e) {
            // Failure isolation: a transient Vault hiccup must not kill the scheduler.
            log.error("Renew tick failed (will retry next interval)", e);
        }
    }

    private synchronized void rotate() {
        if (!started) {
            return;
        }
        try {
            DbCredentials next = provider.fetchCredentials();
            DataSource nextDs = factory.build(next);
            RotatingDataSource rds = rotatingDs;
            if (rds != null) {
                rds.rotate(nextDs);
            }
            activeCreds.set(next);
            log.info("Rotated DataSource: user={}, ttl={}s", next.username(), next.leaseDurationSeconds());
        } catch (RuntimeException e) {
            log.error("Rotate failed; keeping current credentials", e);
        }
    }

    /**
     * Returns the live {@link RotatingDataSource} wrapping the current
     * (possibly freshly rotated) pool. Spring beans that depend on this
     * (e.g. {@code JdbcTemplate}) get a stable reference whose underlying
     * target changes transparently.
     */
    public DataSource dataSource() {
        RotatingDataSource rds = rotatingDs;
        if (rds == null) {
            throw new IllegalStateException("LongJobCredentialManager not started yet");
        }
        return rds;
    }

    @PreDestroy
    public void stop() {
        started = false;
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            scheduler.shutdownNow();
        }
        log.info("LongJobCredentialManager stopped");
    }
}