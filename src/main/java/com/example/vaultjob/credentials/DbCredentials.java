package com.example.vaultjob.credentials;

/**
 * Database credentials returned by Vault's Database Secrets Engine.
 *
 * @param username             short-lived role name (e.g. "v-approle-mydb-rO-w9...")
 * @param password             one-time password Vault generated for the role
 * @param leaseId              Vault lease id, required for renewal / revocation
 * @param leaseDurationSeconds TTL until Vault auto-revokes; 0 = non-expiring
 */
public record DbCredentials(
        String username,
        String password,
        String leaseId,
        long leaseDurationSeconds) {
}