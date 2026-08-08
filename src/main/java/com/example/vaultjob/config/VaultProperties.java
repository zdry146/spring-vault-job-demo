package com.example.vaultjob.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bound to {@code vault.*} in application.yml.
 *
 * Kept intentionally simple (no Lombok) so the demo is one-click readable.
 */
@ConfigurationProperties(prefix = "vault")
public class VaultProperties {

    /** Full Vault address, e.g. https://vault.example.com:8200 */
    private String uri;

    private int connectionTimeoutSeconds = 5;
    private int readTimeoutSeconds = 10;

    /** "approle" is the only one wired up in this demo. */
    private String authMethod = "approle";

    private AppRole approle = new AppRole();

    /** database role name (path: database/creds/<databaseRole>) */
    private String databaseRole;

    private Jdbc jdbc = new Jdbc();

    /**
     * If a job runs longer than lease_duration - this margin, renew the lease.
     * 0 disables renewal (job relies on initial TTL only — fine for short jobs).
     */
    private long renewBeforeExpirySeconds = 300;

    public String getUri() { return uri; }
    public void setUri(String uri) { this.uri = uri; }
    public int getConnectionTimeoutSeconds() { return connectionTimeoutSeconds; }
    public void setConnectionTimeoutSeconds(int v) { this.connectionTimeoutSeconds = v; }
    public int getReadTimeoutSeconds() { return readTimeoutSeconds; }
    public void setReadTimeoutSeconds(int v) { this.readTimeoutSeconds = v; }
    public String getAuthMethod() { return authMethod; }
    public void setAuthMethod(String authMethod) { this.authMethod = authMethod; }
    public AppRole getApprole() { return approle; }
    public void setApprole(AppRole approle) { this.approle = approle; }
    public String getDatabaseRole() { return databaseRole; }
    public void setDatabaseRole(String databaseRole) { this.databaseRole = databaseRole; }
    public Jdbc getJdbc() { return jdbc; }
    public void setJdbc(Jdbc jdbc) { this.jdbc = jdbc; }
    public long getRenewBeforeExpirySeconds() { return renewBeforeExpirySeconds; }
    public void setRenewBeforeExpirySeconds(long v) { this.renewBeforeExpirySeconds = v; }

    public static class AppRole {
        private String roleId;
        private String secretId;
        /** If set, secret-id is read from this file (overrides secretId field). */
        private String secretIdFile;

        public String getRoleId() { return roleId; }
        public void setRoleId(String roleId) { this.roleId = roleId; }
        public String getSecretId() { return secretId; }
        public void setSecretId(String secretId) { this.secretId = secretId; }
        public String getSecretIdFile() { return secretIdFile; }
        public void setSecretIdFile(String secretIdFile) { this.secretIdFile = secretIdFile; }
    }

    public static class Jdbc {
        private String url;

        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
    }
}