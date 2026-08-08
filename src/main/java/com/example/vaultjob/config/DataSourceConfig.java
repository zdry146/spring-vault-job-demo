package com.example.vaultjob.config;

import com.example.vaultjob.credentials.DbCredentials;
import com.example.vaultjob.credentials.VaultCredentialProvider;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * Builds the application's {@link DataSource} using credentials fetched from
 * Vault at startup.
 *
 * <p><strong>Scope:</strong> this pattern is correct for a short batch job
 * whose runtime &lt; Vault lease duration. For long-running jobs that may
 * outlive the lease, see {@code docs/lifecycle.md} for the rotating-pattern
 * alternative.</p>
 */
@Configuration
public class DataSourceConfig {

    private static final Logger log = LoggerFactory.getLogger(DataSourceConfig.class);

    @Bean(destroyMethod = "close")
    public HikariDataSource dataSource(VaultProperties props,
                                       VaultCredentialProvider credentialProvider) {
        DbCredentials creds = credentialProvider.fetchCredentials();
        log.info("Acquired dynamic DB credentials from Vault (user={}, lease_ttl={}s)",
                creds.username(), creds.leaseDurationSeconds());

        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(props.getJdbc().getUrl());
        cfg.setUsername(creds.username());
        cfg.setPassword(creds.password());
        cfg.setMaximumPoolSize(5);
        cfg.setMinimumIdle(1);
        cfg.setConnectionTimeout(5_000);
        cfg.setValidationTimeout(3_000);
        // Even within the lease window, recycle connections so a mid-run role
        // churn on the database side doesn't leave stale connections around.
        cfg.setMaxLifetime(600_000L);   // 10 min
        cfg.setIdleTimeout(120_000L);   // 2 min
        cfg.setPoolName("vault-job-pool");
        return new HikariDataSource(cfg);
    }
}