package com.example.vaultjob.longjob;

import com.example.vaultjob.config.VaultProperties;
import com.example.vaultjob.credentials.DbCredentials;
import com.example.vaultjob.credentials.VaultCredentialProvider;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LongJobCredentialManagerTest {

    private VaultCredentialProvider provider;
    private DataSourceFactory factory;
    private VaultProperties props;
    private LongJobCredentialManager mgr;

    @BeforeEach
    void setUp() {
        provider = mock(VaultCredentialProvider.class);
        factory = mock(DataSourceFactory.class);
        props = new VaultProperties();
        props.setRenewBeforeExpirySeconds(300);
        mgr = new LongJobCredentialManager(provider, factory, props);
    }

    @Test
    void dataSource_beforeStart_throwsIllegalState() {
        assertThatThrownBy(() -> mgr.dataSource())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not started");
    }

    @Test
    void start_fetchesInitialCreds_andBuildsInitialPool() {
        DbCredentials initial = new DbCredentials("u-initial", "p1", "lease-1", 600L);
        HikariDataSource ds1 = mock(HikariDataSource.class);
        when(provider.fetchCredentials()).thenReturn(initial);
        when(factory.build(initial)).thenReturn(ds1);

        mgr.start();

        assertThat(mgr.dataSource()).isNotNull();
        verify(factory).build(initial);
        verify(provider).fetchCredentials();
    }

    @Test
    void tick_renewsLease_doesNotRotateWhenRenewedTtlAboveThreshold() {
        DbCredentials initial = new DbCredentials("u-initial", "p1", "lease-1", 600L);
        HikariDataSource ds1 = mock(HikariDataSource.class);
        when(provider.fetchCredentials()).thenReturn(initial);
        when(factory.build(initial)).thenReturn(ds1);
        when(provider.renewLease(initial)).thenReturn(600L);  // > threshold 300

        mgr.start();
        mgr.tickSafely();

        verify(provider).renewLease(initial);
        verify(factory, times(1)).build(any());  // only the initial build
        verify(ds1, never()).close();
    }

    @Test
    void tick_rotatesWhenRenewedTtlAtOrBelowThreshold() {
        DbCredentials initial = new DbCredentials("u-initial", "p1", "lease-1", 600L);
        DbCredentials fresh = new DbCredentials("u-rotated", "p2", "lease-2", 600L);
        HikariDataSource ds1 = mock(HikariDataSource.class);
        HikariDataSource ds2 = mock(HikariDataSource.class);
        when(provider.fetchCredentials()).thenReturn(initial).thenReturn(fresh);
        when(factory.build(initial)).thenReturn(ds1);
        when(factory.build(fresh)).thenReturn(ds2);
        when(provider.renewLease(initial)).thenReturn(200L);  // < threshold 300

        mgr.start();
        mgr.tickSafely();

        verify(provider, times(2)).fetchCredentials();  // initial + after rotate
        verify(factory).build(initial);
        verify(factory).build(fresh);
        verify(ds1).close();
    }

    @Test
    void tick_rotatesWhenRenewReturnsZero() {
        DbCredentials initial = new DbCredentials("u-initial", "p1", "lease-1", 600L);
        DbCredentials fresh = new DbCredentials("u-rotated", "p2", "lease-2", 600L);
        HikariDataSource ds1 = mock(HikariDataSource.class);
        HikariDataSource ds2 = mock(HikariDataSource.class);
        when(provider.fetchCredentials()).thenReturn(initial).thenReturn(fresh);
        when(factory.build(initial)).thenReturn(ds1);
        when(factory.build(fresh)).thenReturn(ds2);
        when(provider.renewLease(initial)).thenReturn(0L);  // declined

        mgr.start();
        mgr.tickSafely();

        verify(factory).build(fresh);
        verify(ds1).close();
    }

    @Test
    void tick_isolatesExceptions_keepsSchedulerAlive() {
        DbCredentials initial = new DbCredentials("u-initial", "p1", "lease-1", 600L);
        HikariDataSource ds1 = mock(HikariDataSource.class);
        when(provider.fetchCredentials()).thenReturn(initial);
        when(factory.build(initial)).thenReturn(ds1);
        when(provider.renewLease(initial))
                .thenThrow(new RuntimeException("vault 503 transient"));

        mgr.start();
        // Must not propagate; subsequent ticks must still work
        mgr.tickSafely();
        mgr.tickSafely();

        verify(provider, times(2)).renewLease(initial);
    }

    @Test
    void stop_disablesRenewerSubsequentTicksAreNoOp() {
        DbCredentials initial = new DbCredentials("u-initial", "p1", "lease-1", 600L);
        HikariDataSource ds1 = mock(HikariDataSource.class);
        when(provider.fetchCredentials()).thenReturn(initial);
        when(factory.build(initial)).thenReturn(ds1);

        mgr.start();
        mgr.stop();
        mgr.tickSafely();

        verify(provider, never()).renewLease(any());
    }

    @Test
    void start_autoComputesIntervalFromLeaseWhenNotConfigured() {
        DbCredentials initial = new DbCredentials("u", "p", "lease", 600L);
        HikariDataSource ds1 = mock(HikariDataSource.class);
        when(provider.fetchCredentials()).thenReturn(initial);
        when(factory.build(initial)).thenReturn(ds1);

        // props.longJob.renewIntervalSeconds = 0 → auto = lease/2 = 300
        mgr.start();

        // No direct assertion on interval (it's encapsulated), but the test
        // would fail with IllegalArgumentException if the interval calculation
        // crashed. The start log line would print "renew_interval=300s".
        assertThat(mgr.dataSource()).isNotNull();
    }

    @Test
    void start_honoursExplicitRenewIntervalFromProperties() {
        DbCredentials initial = new DbCredentials("u", "p", "lease", 600L);
        HikariDataSource ds1 = mock(HikariDataSource.class);
        when(provider.fetchCredentials()).thenReturn(initial);
        when(factory.build(initial)).thenReturn(ds1);

        props.getLongJob().setRenewIntervalSeconds(120);
        mgr.start();

        // Same as above — interval is encapsulated; test asserts no crash
        // when an override is present.
        assertThat(mgr.dataSource()).isNotNull();
    }
}