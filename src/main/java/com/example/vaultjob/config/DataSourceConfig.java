package com.example.vaultjob.config;

import com.example.vaultjob.credentials.DbCredentials;
import com.example.vaultjob.credentials.VaultCredentialProvider;
import com.example.vaultjob.longjob.DataSourceFactory;
import com.example.vaultjob.longjob.LongJobCredentialManager;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * Builds the application's {@link DataSource} using credentials fetched from
 * Vault at startup.
 *
 * <p>Two beans are registered, mutually exclusive by configuration:</p>
 * <ul>
 *   <li><b>{@code shortJobDataSource}</b> (default) — fetches one credential pair
 *       at startup and builds a single pool. Correct when job runtime
 *       &lt; Vault lease duration. Active when
 *       {@code vault.long-job.enabled} is unset or {@code false}.</li>
 *   <li><b>{@code longJobDataSource}</b> — delegates to {@link LongJobCredentialManager},
 *       which periodically renews / rotates the credential and swaps the pool
 *       transparently. Active only when {@code vault.long-job.enabled=true}.</li>
 * </ul>
 */
@Configuration
public class DataSourceConfig {

    private static final Logger log = LoggerFactory.getLogger(DataSourceConfig.class);

    /**
     * Short-job pool. Eagerly fetches credentials at startup.
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "vault.long-job.enabled", havingValue = "false", matchIfMissing = true)
    @ConditionalOnMissingBean(DataSource.class)
    public HikariDataSource shortJobDataSource(VaultProperties props,
                                               VaultCredentialProvider credentialProvider) {
        DbCredentials creds = credentialProvider.fetchCredentials();
        log.info("Acquired dynamic DB credentials from Vault (user={}, lease_ttl={}s) — short-job mode",
                creds.username(), creds.leaseDurationSeconds());
        return new DataSourceFactory(props).build(creds);
    }

    /**
     * Long-job pool. Delegates to {@link LongJobCredentialManager} which is
     * itself a {@code @ConditionalOnProperty(... havingValue = "true")} bean.
     */
    @Bean
    @ConditionalOnProperty(name = "vault.long-job.enabled", havingValue = "true")
    public DataSource longJobDataSource(LongJobCredentialManager manager) {
        log.info("Long-job mode active; DataSource delegates to {}",
                manager.getClass().getSimpleName());
        return manager.dataSource();
    }
}