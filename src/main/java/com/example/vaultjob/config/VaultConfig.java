package com.example.vaultjob.config;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.vault.authentication.AppRoleAuthentication;
import org.springframework.vault.authentication.AppRoleAuthenticationOptions;
import org.springframework.vault.authentication.AppRoleAuthenticationOptions.RoleId;
import org.springframework.vault.authentication.AppRoleAuthenticationOptions.SecretId;
import org.springframework.vault.client.VaultEndpoint;
import org.springframework.vault.core.VaultTemplate;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Wires up a {@link VaultTemplate} configured for AppRole authentication.
 *
 * Why AppRole for a VM job: it is the canonical, cloud-agnostic auth method
 * suitable for non-interactive, non-K8s workloads. AWS auth (EC2 metadata) is
 * also viable; that would mean swapping this bean for an
 * {@code Ec2Authentication} equivalent.
 */
@Configuration
@EnableConfigurationProperties(VaultProperties.class)
public class VaultConfig {

    @Bean
    public VaultTemplate vaultTemplate(VaultProperties props,
                                       ObjectProvider<WrappedSecretIdResolver> wrappedResolverProvider) {
        VaultEndpoint endpoint = VaultEndpoint.from(URI.create(props.getUri()));

        // Build a RestTemplate with explicit connect/read timeouts so a stuck
        // Vault can never wedge the job indefinitely.
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(props.getConnectionTimeoutSeconds() * 1000);
        factory.setReadTimeout(props.getReadTimeoutSeconds() * 1000);
        RestTemplate restTemplate = new RestTemplate(factory);

        if (!"approle".equalsIgnoreCase(props.getAuthMethod())) {
            throw new IllegalStateException(
                    "Unsupported vault.auth-method: " + props.getAuthMethod()
                            + " (this demo only wires approle)");
        }

        // Priority for secret_id:
        //   1. WrappedSecretIdResolver (active only when VAULT_WRAPPING_TOKEN is set)
        //   2. File path (VAULT_SECRET_ID_FILE)
        //   3. Env var (VAULT_SECRET_ID) - dev only
        WrappedSecretIdResolver wrappedResolver = wrappedResolverProvider.getIfAvailable();
        String secretId = (wrappedResolver != null)
                ? wrappedResolver.resolve()
                : resolveSecretId(props);

        AppRoleAuthenticationOptions options = AppRoleAuthenticationOptions.builder()
                .roleId(RoleId.provided(props.getApprole().getRoleId()))
                .secretId(SecretId.provided(secretId))
                .build();

        return new VaultTemplate(endpoint, new AppRoleAuthentication(options, restTemplate));
    }

    /**
     * Prefer VAULT_SECRET_ID_FILE (path on disk) over the raw env var. This lets
     * ops mount a secret from a secret manager without putting the value in an
     * env var that might be logged.
     */
    private static String resolveSecretId(VaultProperties props) {
        String file = props.getApprole().getSecretIdFile();
        if (file != null && !file.isBlank()) {
            try {
                return Files.readString(Paths.get(file)).trim();
            } catch (IOException e) {
                throw new IllegalStateException(
                        "Failed to read vault.secret-id-file: " + file, e);
            }
        }
        String direct = props.getApprole().getSecretId();
        if (direct == null || direct.isBlank()) {
            throw new IllegalStateException(
                    "No AppRole secret_id available: set VAULT_WRAPPING_TOKEN, "
                            + "VAULT_SECRET_ID_FILE, or VAULT_SECRET_ID");
        }
        return direct;
    }
}