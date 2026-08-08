package com.example.vaultjob.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Map;

/**
 * Resolves a Vault AppRole {@code secret_id} by unwrapping a
 * <b>response-wrapping token</b> at job startup.
 *
 * <p>This is the production-grade pattern for batch jobs that don't want
 * any {@code secret_id} sitting in config or env vars at deploy time:</p>
 *
 * <ol>
 *   <li>CI/CD deploy step runs {@code vault token create -wrap-ttl=5m -policy=...}
 *       and stores the resulting wrapping token in a short-lived secret
 *       (k8s Secret / SSM Parameter / Vault Agent file).</li>
 *   <li>Job starts, finds {@code VAULT_WRAPPING_TOKEN} (or
 *       {@code VAULT_WRAPPING_TOKEN_FILE}), calls
 *       {@code POST /v1/sys/wrapping/unwrap} with
 *       {@code X-Vault-Token: <wrapping-token>}.</li>
 *   <li>Vault returns the wrapped response, which contains a fresh
 *       {@code secret_id} for the AppRole.</li>
 *   <li>Job uses that {@code secret_id} for AppRole auth; existing
 *       {@link com.example.vaultjob.config.VaultConfig} wires it up.</li>
 * </ol>
 *
 * <p>The wrapping token is single-use, so the unwrap call consumes it.
 * The resulting {@code secret_id} is governed by its own AppRole
 * {@code secret_id_ttl} (typically 30m-1h). If the job runs longer than
 * that, Pattern B (renew + rotate) kicks in.</p>
 *
 * <p>Active only when {@code vault.approle.wrapping-token} (or its
 * {@code -file} variant) is set; otherwise the file-based or env-based
 * resolvers in {@link VaultConfig} take over.</p>
 */
@Component
@ConditionalOnProperty(name = "vault.approle.wrapping-token")
public class WrappedSecretIdResolver {

    private static final Logger log = LoggerFactory.getLogger(WrappedSecretIdResolver.class);

    private final RestTemplate restTemplate;
    private final String vaultUri;
    private final String wrappingToken;
    private final String outputFile;

    public WrappedSecretIdResolver(VaultProperties props) {
        this(props, new RestTemplate());
    }

    /** Package-private constructor for tests (inject a mockable RestTemplate). */
    WrappedSecretIdResolver(VaultProperties props, RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
        this.vaultUri = props.getUri();
        this.wrappingToken = resolveWrappingToken(props);
        this.outputFile = props.getApprole().getUnwrapOutputFile();
    }

    private static String resolveWrappingToken(VaultProperties props) {
        // file overrides env (per the same pattern as secret_id)
        String file = props.getApprole().getWrappingTokenFile();
        if (file != null && !file.isBlank()) {
            try {
                return Files.readString(Paths.get(file)).trim();
            } catch (IOException e) {
                throw new IllegalStateException(
                        "Failed to read vault.approle.wrapping-token-file: " + file, e);
            }
        }
        String direct = props.getApprole().getWrappingToken();
        if (direct == null || direct.isBlank()) {
            // @ConditionalOnProperty ensures this isn't reached when both
            // are absent, but be defensive.
            throw new IllegalStateException(
                    "Neither VAULT_WRAPPING_TOKEN nor VAULT_WRAPPING_TOKEN_FILE is set");
        }
        return direct;
    }

    /**
     * Calls {@code POST /v1/sys/wrapping/unwrap} and returns the unwrapped
     * {@code secret_id}. On success, optionally writes it to a file
     * (mode 600 best-effort) if {@code vault.approle.unwrap-output-file}
     * is set, so subsequent runs (or sibling processes) can read it
     * without repeating the unwrap.
     */
    public String resolve() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Vault-Token", wrappingToken);
        HttpEntity<Void> request = new HttpEntity<>(headers);

        ResponseEntity<Map<String, Object>> response;
        try {
            response = restTemplate.exchange(
                    vaultUri + "/v1/sys/wrapping/unwrap",
                    HttpMethod.POST,
                    request,
                    /* raw Map<String,Object> body */ (Class) Map.class);
        } catch (RestClientException e) {
            throw new IllegalStateException(
                    "Vault unwrap call failed (is the wrapping token valid and single-use unspent?)", e);
        }

        Map<String, Object> body = response.getBody();
        if (body == null) {
            throw new IllegalStateException("Vault unwrap returned empty body");
        }
        Object dataObj = body.get("data");
        if (!(dataObj instanceof Map<?, ?> dataMap)) {
            throw new IllegalStateException(
                    "Vault unwrap response missing 'data' object: " + body);
        }
        Object secretIdObj = dataMap.get("secret_id");
        if (!(secretIdObj instanceof String secretId) || secretId.isBlank()) {
            throw new IllegalStateException(
                    "Vault unwrap response missing non-empty 'secret_id': " + dataMap);
        }

        log.info("Successfully unwrapped secret_id (len={}) from Vault", secretId.length());

        if (outputFile != null && !outputFile.isBlank()) {
            writeToFile(secretId);
        }
        return secretId;
    }

    private void writeToFile(String secretId) {
        try {
            Path path = Paths.get(outputFile);
            Files.writeString(path, secretId,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            // Best-effort chmod 600; ignored on Windows / when FS doesn't support POSIX perms
            try {
                Files.setPosixFilePermissions(path,
                        java.util.EnumSet.of(
                                java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE));
            } catch (UnsupportedOperationException | java.io.IOException ignored) {
                // POSIX perms not supported on this FS; secret_id is still in a file
                // owned by the current user.
            }
            log.info("Wrote unwrapped secret_id to {} (file 600)", outputFile);
        } catch (IOException e) {
            // Non-fatal: the unwrapped secret_id is still in memory and usable.
            log.warn("Failed to write unwrapped secret_id to {}: (file)",
                    outputFile, e);
        }
    }
}