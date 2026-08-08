package com.example.vaultjob.credentials;

import com.example.vaultjob.config.VaultProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.vault.core.VaultTemplate;
import org.springframework.vault.support.VaultResponse;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VaultCredentialProviderTest {

    private VaultTemplate vaultTemplate;
    private VaultProperties properties;
    private VaultCredentialProvider provider;

    @BeforeEach
    void setUp() {
        vaultTemplate = mock(VaultTemplate.class);
        properties = new VaultProperties();
        properties.setDatabaseRole("mydb-role");
        provider = new VaultCredentialProvider(vaultTemplate, properties);
    }

    @Test
    void fetchCredentials_returnsUsernameAndPasswordFromVaultResponse() {
        VaultResponse response = new VaultResponse();
        response.setData(Map.of(
                "username", "v-approle-abc123",
                "password", "s3cret-generated-by-vault"));
        response.setLeaseId("database/creds/mydb-role/lease-uuid-xyz");
        response.setLeaseDuration(300L);
        when(vaultTemplate.read("database/creds/mydb-role")).thenReturn(response);

        DbCredentials creds = provider.fetchCredentials();

        assertThat(creds.username()).isEqualTo("v-approle-abc123");
        assertThat(creds.password()).isEqualTo("s3cret-generated-by-vault");
        assertThat(creds.leaseId()).isEqualTo("database/creds/mydb-role/lease-uuid-xyz");
        assertThat(creds.leaseDurationSeconds()).isEqualTo(300L);
        assertThat(provider.trackedLeaseCount()).isEqualTo(1);
    }

    @Test
    void fetchCredentials_throwsWhenResponseMissingFields() {
        VaultResponse response = new VaultResponse();
        response.setData(Map.of("only-one-field", "oops"));
        when(vaultTemplate.read(eq("database/creds/mydb-role"))).thenReturn(response);

        assertThatThrownBy(() -> provider.fetchCredentials())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing username/password");
    }

    @Test
    void revokeAll_callsVaultForEachIssuedLease() {
        VaultResponse r1 = new VaultResponse();
        r1.setData(Map.of("username", "u1", "password", "p1"));
        r1.setLeaseId("lease-1");
        r1.setLeaseDuration(60L);
        VaultResponse r2 = new VaultResponse();
        r2.setData(Map.of("username", "u2", "password", "p2"));
        r2.setLeaseId("lease-2");
        r2.setLeaseDuration(60L);

        when(vaultTemplate.read("database/creds/mydb-role"))
                .thenReturn(r1).thenReturn(r2);

        provider.fetchCredentials();
        provider.fetchCredentials();
        assertThat(provider.trackedLeaseCount()).isEqualTo(2);

        provider.revokeAll();

        verify(vaultTemplate, times(2)).write(eq("sys/leases/revoke"), any(Map.class));
        assertThat(provider.trackedLeaseCount()).isZero();
    }

    @Test
    void revokeAll_continuesEvenIfOneRevocationFails() {
        VaultResponse r = new VaultResponse();
        r.setData(Map.of("username", "u", "password", "p"));
        r.setLeaseId("lease-bad");
        r.setLeaseDuration(60L);
        when(vaultTemplate.read("database/creds/mydb-role")).thenReturn(r);

        // First revoke fails, but revokeAll should not propagate the exception
        doThrow(new RuntimeException("vault 503"))
                .when(vaultTemplate).write(eq("sys/leases/revoke"), any(Map.class));

        provider.fetchCredentials();
        provider.revokeAll();  // must NOT throw
        assertThat(provider.trackedLeaseCount()).isZero();
    }
}