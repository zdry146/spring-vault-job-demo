package com.example.vaultjob.revoke;

import com.example.vaultjob.credentials.VaultCredentialProvider;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Revokes every Vault lease this JVM issued when the application context
 * is being closed. Wired to {@link PreDestroy} so it fires on graceful
 * shutdown (Spring's normal exit path) as well as on
 * {@code SpringApplication.exit()} in {@code DemoApplication}.
 *
 * <p>Combined with a JVM shutdown hook, this also covers SIGTERM from a
 * container / orchestrator — but for the strongest guarantee, register an
 * explicit shutdown hook as well (see {@link #registerJvmHook}).</p>
 */
@Component
public class LeaseRevokingShutdownHook {

    private static final Logger log = LoggerFactory.getLogger(LeaseRevokingShutdownHook.class);

    private final VaultCredentialProvider credentialProvider;

    public LeaseRevokingShutdownHook(VaultCredentialProvider credentialProvider) {
        this.credentialProvider = credentialProvider;
    }

    @PreDestroy
    public void onShutdown() {
        log.info("Revoking outstanding Vault leases on shutdown");
        credentialProvider.revokeAll();
    }

    /**
     * Optional belt-and-braces: catch SIGTERM / SIGINT that bypass Spring's
     * normal close path. Returns the hook so the caller can deregister if
     * they want (not used in this demo).
     */
    public Thread registerJvmHook() {
        Thread hook = new Thread(() -> {
            log.info("JVM shutdown hook firing; revoking leases");
            credentialProvider.revokeAll();
        }, "vault-lease-revoke");
        Runtime.getRuntime().addShutdownHook(hook);
        return hook;
    }
}