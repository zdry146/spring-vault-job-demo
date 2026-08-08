package com.example.vaultjob.longjob;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class VaultMetricsTest {

    private MeterRegistry registry;
    private VaultMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new VaultMetrics(registry);
    }

    @Test
    void recordRenewalSuccess_incrementsSuccessCounter_andUpdatesTimestamp() throws Exception {
        long before = metrics.lastSuccessfulRenewTimestamp();
        metrics.recordRenewalSuccess();

        assertThat(metrics.lastSuccessfulRenewTimestamp()).isGreaterThan(before);
        assertThat(registry.get("vault.lease.renewals").tag("result", "success").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    void recordRenewalFailure_incrementsFailureCounter_only() throws Exception {
        long before = metrics.lastSuccessfulRenewTimestamp();
        metrics.recordRenewalFailure();

        // Timestamp is NOT updated on failure
        assertThat(metrics.lastSuccessfulRenewTimestamp()).isEqualTo(before);
        assertThat(registry.get("vault.lease.renewals").tag("result", "failure").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    void recordRotation_incrementsCounter_andRecordsTimer() {
        metrics.recordRotation(Duration.ofMillis(150));
        metrics.recordRotation(Duration.ofMillis(250));

        assertThat(registry.get("vault.lease.rotations").counter().count()).isEqualTo(2.0);
        assertThat(registry.get("vault.lease.rotation.duration").timer().count()).isEqualTo(2L);
        assertThat(registry.get("vault.lease.rotation.duration").timer().totalTime(TimeUnit.MILLISECONDS))
                .isBetween(390.0, 410.0);  // 150 + 250 = 400ms ± rounding
    }

    @Test
    void lastRenewTimestampGauge_readsFromAtomicLong() throws Exception {
        // Initial value = 0
        assertThat(registry.get("vault.lease.last_renew_timestamp").gauge().value()).isEqualTo(0.0);

        metrics.recordRenewalSuccess();
        Thread.sleep(2);  // ensure timestamp moves at all on fast clocks
        long ts = metrics.lastSuccessfulRenewTimestamp();
        assertThat(registry.get("vault.lease.last_renew_timestamp").gauge().value())
                .isEqualTo((double) ts);
    }

    @Test
    void metricsAreRegisteredOnce_sharedAcrossInstances() {
        // Sanity: re-creating VaultMetrics with the SAME registry should not double
        // the values. This works in Micrometer because Counter.builder().register()
        // returns the existing instance if the (name, tags) pair is unique.
        VaultMetrics second = new VaultMetrics(registry);
        second.recordRenewalSuccess();
        second.recordRenewalSuccess();

        assertThat(registry.get("vault.lease.renewals").tag("result", "success").counter().count())
                .isEqualTo(2.0);
    }
}