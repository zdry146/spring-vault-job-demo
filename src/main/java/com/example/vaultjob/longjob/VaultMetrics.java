package com.example.vaultjob.longjob;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Micrometer-backed metrics for the long-job Vault lifecycle.
 *
 * <p>Exposed metrics:</p>
 * <ul>
 *   <li>{@code vault.lease.renewals{result=success|failure}} \u2014 counter</li>
 *   <li>{@code vault.lease.rotations} \u2014 counter (each rotation issues a new credential pair)</li>
 *   <li>{@code vault.lease.rotation.duration} \u2014 timer</li>
 *   <li>{@code vault.lease.last_renew_timestamp} \u2014 gauge (epoch ms of last successful renew;
 *       0 means none yet)</li>
 * </ul>
 *
 * <p>Only registered when both the long-job mode is on AND a {@link MeterRegistry}
 * bean is on the context (i.e. {@code spring-boot-starter-actuator} +
 * {@code micrometer-registry-prometheus} are on the classpath, or any other
 * registry the user wires up).</p>
 */
@Component
@ConditionalOnProperty(name = "vault.long-job.enabled", havingValue = "true")
@ConditionalOnBean(MeterRegistry.class)
public class VaultMetrics {

    private static final Logger log = LoggerFactory.getLogger(VaultMetrics.class);

    private final Counter renewalsSuccess;
    private final Counter renewalsFailure;
    private final Counter rotations;
    private final Timer rotationDuration;
    private final AtomicLong lastSuccessfulRenewTimestamp = new AtomicLong(0L);

    public VaultMetrics(MeterRegistry registry) {
        this.renewalsSuccess = Counter.builder("vault.lease.renewals")
                .description("Vault lease renewals issued by the long-job renewer")
                .tag("result", "success")
                .register(registry);
        this.renewalsFailure = Counter.builder("vault.lease.renewals")
                .description("Vault lease renewals issued by the long-job renewer")
                .tag("result", "failure")
                .register(registry);
        this.rotations = Counter.builder("vault.lease.rotations")
                .description("Credential rotations (fresh DB role issued by Vault)")
                .register(registry);
        this.rotationDuration = Timer.builder("vault.lease.rotation.duration")
                .description("End-to-end rotation time (fetch + build pool + swap + close)")
                .register(registry);
        Gauge.builder("vault.lease.last_renew_timestamp",
                        lastSuccessfulRenewTimestamp, AtomicLong::doubleValue)
                .description("Epoch milliseconds of the last successful lease renewal (0 = none yet)")
                .register(registry);
        log.info("VaultMetrics registered: renewals/rotations/last_renew_timestamp");
    }

    public void recordRenewalSuccess() {
        renewalsSuccess.increment();
        lastSuccessfulRenewTimestamp.set(System.currentTimeMillis());
    }

    public void recordRenewalFailure() {
        renewalsFailure.increment();
    }

    public void recordRotation(Duration duration) {
        rotations.increment();
        rotationDuration.record(duration);
    }

    /** Visible for tests and for {@link VaultHealthIndicator}. */
    public long lastSuccessfulRenewTimestamp() {
        return lastSuccessfulRenewTimestamp.get();
    }
}