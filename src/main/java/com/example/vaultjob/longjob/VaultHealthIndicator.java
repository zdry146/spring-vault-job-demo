package com.example.vaultjob.longjob;

import com.example.vaultjob.config.VaultProperties;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * {@code /actuator/health/vault} endpoint. UP iff the long-job renewer has
 * successfully renewed the lease within {@code threshold} (default:
 * 2 \u00d7 renew interval).
 *
 * <p>Three states:</p>
 * <ul>
 *   <li><b>UNKNOWN</b> \u2014 startup grace period; no renew has happened yet.
 *       Resolves once the first renew tick fires (auto: within half the lease TTL).</li>
 *   <li><b>UP</b> \u2014 last successful renewal within {@code threshold}.</li>
 *   <li><b>DOWN</b> \u2014 last successful renewal older than {@code threshold}.
 *       Likely cause: Vault unreachable for too long, renewer dead, or JVM starved.</li>
 * </ul>
 *
 * <p>Only registered when both the long-job mode is on AND {@link VaultMetrics}
 * is on the context (which itself requires a {@code MeterRegistry}).</p>
 */
@Component
@ConditionalOnProperty(name = "vault.long-job.enabled", havingValue = "true")
@ConditionalOnBean(VaultMetrics.class)
public class VaultHealthIndicator implements HealthIndicator {

    private final VaultMetrics metrics;
    private final long thresholdSeconds;

    public VaultHealthIndicator(VaultMetrics metrics, VaultProperties props) {
        this.metrics = metrics;
        // If user configured a custom interval, threshold = 2x that. Otherwise
        // assume the auto default (300s lease => 150s renew interval) and use 300s.
        long intervalSec = props.getLongJob().getRenewIntervalSeconds();
        this.thresholdSeconds = intervalSec > 0 ? intervalSec * 2L : 300L;
    }

    @Override
    public Health health() {
        long lastRenewMs = metrics.lastSuccessfulRenewTimestamp();
        long now = System.currentTimeMillis();

        if (lastRenewMs == 0L) {
            // No renewal yet \u2014 still within startup grace window.
            return Health.unknown()
                    .withDetail("reason", "no successful renewal yet (within startup grace window)")
                    .withDetail("threshold_seconds", thresholdSeconds)
                    .build();
        }
        long ageSeconds = (now - lastRenewMs) / 1000L;
        if (ageSeconds > thresholdSeconds) {
            return Health.down()
                    .withDetail("reason", "last successful renewal is too old")
                    .withDetail("age_seconds", ageSeconds)
                    .withDetail("threshold_seconds", thresholdSeconds)
                    .build();
        }
        return Health.up()
                .withDetail("age_seconds", ageSeconds)
                .withDetail("threshold_seconds", thresholdSeconds)
                .build();
    }
}