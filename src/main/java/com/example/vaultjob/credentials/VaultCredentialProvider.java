package com.example.vaultjob.credentials;

import com.example.vaultjob.config.VaultProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.vault.core.VaultTemplate;
import org.springframework.vault.support.VaultResponse;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Fetches dynamic database credentials from Vault's database secrets engine,
 * and tracks every issued lease so they can be revoked when the JVM shuts
 * down (graceful) or at job completion.
 *
 * <p>Path requested: {@code database/creds/<vault.database-role>}</p>
 */
@Component
public class VaultCredentialProvider {

    private static final Logger log = LoggerFactory.getLogger(VaultCredentialProvider.class);

    private final VaultTemplate vaultTemplate;
    private final VaultProperties properties;
    private final List<DbCredentials> issued = new CopyOnWriteArrayList<>();

    public VaultCredentialProvider(VaultTemplate vaultTemplate, VaultProperties properties) {
        this.vaultTemplate = vaultTemplate;
        this.properties = properties;
    }

    /**
     * Request a fresh pair of database credentials from Vault.
     *
     * <p>The lease id is captured so the lease can be renewed or revoked later.
     * For a short-running job you typically don't renew — just let the lease
     * expire naturally, or call {@link #revokeAll()} on shutdown.</p>
     */
    public DbCredentials fetchCredentials() {
        String path = "database/creds/" + properties.getDatabaseRole();
        VaultResponse response = vaultTemplate.read(path);

        Map<String, Object> data = response.getData();
        if (data == null || !data.containsKey("username") || !data.containsKey("password")) {
            throw new IllegalStateException(
                    "Vault response for " + path + " missing username/password: " + data);
        }

        DbCredentials creds = new DbCredentials(
                (String) data.get("username"),
                (String) data.get("password"),
                response.getLeaseId(),
                response.getLeaseDuration());
        issued.add(creds);
        log.info("Fetched credentials from Vault path={} lease_ttl={}s",
                path, creds.leaseDurationSeconds());
        return creds;
    }

    /**
     * Renew the lease for an existing credential set, extending its TTL.
     * Returns the new lease duration in seconds. Returns 0 if Vault declines
     * to renew (caller should re-fetch a fresh pair instead).
     */
    public long renewLease(DbCredentials creds) {
        if (creds.leaseId() == null) {
            return 0L;
        }
        try {
            VaultResponse resp = vaultTemplate.write(
                    "sys/leases/renew",
                    Map.of("lease_id", creds.leaseId()));
            return resp.getLeaseDuration();
        } catch (RuntimeException e) {
            log.warn("Lease renewal failed for {}: {}", creds.leaseId(), e.getMessage());
            return 0L;
        }
    }

    /**
     * Revoke every lease this provider has issued. Safe to call multiple times.
     * Failures are logged but never rethrown — we always want shutdown to proceed.
     */
    public void revokeAll() {
        for (DbCredentials creds : issued) {
            try {
                vaultTemplate.write("sys/leases/revoke", Map.of("lease_id", creds.leaseId()));
                log.info("Revoked lease {}", creds.leaseId());
            } catch (RuntimeException e) {
                log.warn("Failed to revoke lease {}: {}", creds.leaseId(), e.getMessage());
            }
        }
        issued.clear();
    }

    /** Visible for tests: how many leases are still outstanding. */
    int trackedLeaseCount() {
        return issued.size();
    }
}