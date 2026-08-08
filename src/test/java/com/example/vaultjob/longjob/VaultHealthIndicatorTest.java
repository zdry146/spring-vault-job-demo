package com.example.vaultjob.longjob;

import com.example.vaultjob.config.VaultProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VaultHealthIndicatorTest {

    private VaultMetrics metrics;
    private VaultProperties props;
    private VaultHealthIndicator indicator;

    @BeforeEach
    void setUp() {
        metrics = mock(VaultMetrics.class);
        props = new VaultProperties();
        indicator = new VaultHealthIndicator(metrics, props);
    }

    @Test
    void health_returnsUnknownWhenNoRenewalYet() {
        when(metrics.lastSuccessfulRenewTimestamp()).thenReturn(0L);

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UNKNOWN);
        assertThat(health.getDetails()).containsKey("reason");
        assertThat(health.getDetails().get("reason").toString()).contains("startup grace");
    }

    @Test
    void health_returnsUpWhenRecentRenewal() {
        long now = System.currentTimeMillis();
        when(metrics.lastSuccessfulRenewTimestamp()).thenReturn(now - 10_000L);  // 10s ago

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails().get("age_seconds")).isEqualTo(10L);
    }

    @Test
    void health_returnsDownWhenRenewalTooOld() {
        long now = System.currentTimeMillis();
        props.getLongJob().setRenewIntervalSeconds(60);  // threshold = 120s
        // Re-create indicator with new props
        indicator = new VaultHealthIndicator(metrics, props);
        when(metrics.lastSuccessfulRenewTimestamp()).thenReturn(now - 200_000L);  // 200s ago

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails().get("reason").toString()).contains("too old");
        assertThat(health.getDetails().get("age_seconds")).isEqualTo(200L);
        assertThat(health.getDetails().get("threshold_seconds")).isEqualTo(120L);
    }

    @Test
    void health_thresholdUsesConfiguredRenewInterval() {
        props.getLongJob().setRenewIntervalSeconds(10);  // threshold = 20s
        indicator = new VaultHealthIndicator(metrics, props);
        long now = System.currentTimeMillis();
        when(metrics.lastSuccessfulRenewTimestamp()).thenReturn(now - 30_000L);  // 30s > 20s threshold

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails().get("threshold_seconds")).isEqualTo(20L);
    }

    @Test
    void health_thresholdDefaultsTo300sWhenAutoInterval() {
        // props.getLongJob().getRenewIntervalSeconds() == 0 (auto)
        long now = System.currentTimeMillis();
        when(metrics.lastSuccessfulRenewTimestamp()).thenReturn(now - 350_000L);  // 350s > 300s default

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails().get("threshold_seconds")).isEqualTo(300L);
    }
}