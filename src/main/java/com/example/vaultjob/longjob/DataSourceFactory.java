package com.example.vaultjob.longjob;

import com.example.vaultjob.config.VaultProperties;
import com.example.vaultjob.credentials.DbCredentials;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.util.UUID;

/**
 * Builds a fresh {@link HikariDataSource} from a {@link DbCredentials} pair.
 *
 * <p>Used both by the short-job config (one pool, kept for the JVM lifetime)
 * and the long-job manager (a new pool every time credentials rotate).</p>
 *
 * <p>Each pool gets a unique name (UUID-suffixed) so rotations are visible
 * as distinct pools in JMX / metrics.</p>
 */
public class DataSourceFactory {

    private final VaultProperties props;

    public DataSourceFactory(VaultProperties props) {
        this.props = props;
    }

    public HikariDataSource build(DbCredentials creds) {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(props.getJdbc().getUrl());
        cfg.setUsername(creds.username());
        cfg.setPassword(creds.password());
        cfg.setMaximumPoolSize(5);
        cfg.setMinimumIdle(1);
        cfg.setConnectionTimeout(5_000);
        cfg.setValidationTimeout(3_000);
        cfg.setMaxLifetime(600_000L);   // 10 min
        cfg.setIdleTimeout(120_000L);   // 2 min
        cfg.setPoolName("vault-job-pool-" + UUID.randomUUID().toString().substring(0, 8));
        return new HikariDataSource(cfg);
    }
}