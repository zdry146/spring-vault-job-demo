package com.example.vaultjob.job;

import com.example.vaultjob.credentials.DbCredentials;
import com.example.vaultjob.credentials.VaultCredentialProvider;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class BatchJobRunnerTest {

    @Test
    void run_queriesPublicTableCountWithVaultCredentials() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        VaultCredentialProvider provider = mock(VaultCredentialProvider.class);
        when(provider.fetchCredentials()).thenReturn(
                new DbCredentials("v-approle-test", "pw", "lease-1", 300L));
        when(jdbc.queryForObject(any(String.class), eq(Integer.class))).thenReturn(7);

        BatchJobRunner runner = new BatchJobRunner(jdbc, provider);
        runner.run();

        verify(provider).fetchCredentials();
        verify(jdbc).queryForObject(any(String.class), eq(Integer.class));
        verifyNoMoreInteractions(jdbc, provider);
    }
}