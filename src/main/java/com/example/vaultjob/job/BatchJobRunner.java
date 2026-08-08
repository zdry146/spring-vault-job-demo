package com.example.vaultjob.job;

import com.example.vaultjob.credentials.DbCredentials;
import com.example.vaultjob.credentials.VaultCredentialProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Sample batch logic — runs once when the Spring context is ready.
 *
 * <p>Active only in short-job mode ({@code vault.long-job.enabled=false},
 * the default). For long-job mode, see
 * {@link com.example.vaultjob.longjob.LongJobBatchRunner} which adds
 * rotation-aware retry.</p>
 *
 * <p>The injected {@link JdbcTemplate} uses the Hikari pool built in
 * {@link com.example.vaultjob.config.DataSourceConfig}, whose credentials
 * came from Vault. This demonstrates the typical batch-job pattern:</p>
 * <ol>
 *   <li>Spring boots → DataSource bean built using Vault creds</li>
 *   <li>CommandLineRunner fires → runs the job against the DB</li>
 *   <li>JVM shutdown → LeaseRevokingShutdownHook revokes the lease</li>
 * </ol>
 */
@Component
@ConditionalOnProperty(name = "vault.long-job.enabled", havingValue = "false", matchIfMissing = true)
public class BatchJobRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(BatchJobRunner.class);

    private final JdbcTemplate jdbcTemplate;
    private final VaultCredentialProvider credentialProvider;

    public BatchJobRunner(JdbcTemplate jdbcTemplate, VaultCredentialProvider credentialProvider) {
        this.jdbcTemplate = jdbcTemplate;
        this.credentialProvider = credentialProvider;
    }

    @Override
    public void run(String... args) {
        log.info("=== Batch job started ===");

        // In this short-job demo we already fetched credentials inside
        // DataSourceConfig, so this is informational. For a longer-running job
        // you would call fetchCredentials() again here to issue a fresh lease
        // before the old one expires (see docs/lifecycle.md).
        DbCredentials active = credentialProvider.fetchCredentials();
        log.info("Active credential: user={}, ttl={}s, lease={}",
                active.username(), active.leaseDurationSeconds(), active.leaseId());

        // Demo query — proves the dynamic credentials work against the real DB.
        Integer tableCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'public'",
                Integer.class);
        log.info("Public tables visible to dynamic user: {}", tableCount);

        log.info("=== Batch job finished cleanly ===");
    }
}