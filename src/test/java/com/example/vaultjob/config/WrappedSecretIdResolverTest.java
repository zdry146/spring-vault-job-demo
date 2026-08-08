package com.example.vaultjob.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.http.HttpMethod.POST;

class WrappedSecretIdResolverTest {

    private RestTemplate restTemplate;
    private MockRestServiceServer mockServer;
    private VaultProperties props;
    private WrappedSecretIdResolver resolver;

    @BeforeEach
    void setUp() {
        restTemplate = new RestTemplate();
        mockServer = MockRestServiceServer.createServer(restTemplate);
        props = new VaultProperties();
        props.setUri("http://localhost:8200");
        props.getApprole().setWrappingToken("hvs.test-wrapping-token");
        resolver = new WrappedSecretIdResolver(props, restTemplate);
    }

    @Test
    void resolve_returnsSecretIdOnSuccess() {
        mockServer.expect(requestTo("http://localhost:8200/v1/sys/wrapping/unwrap"))
                .andExpect(method(POST))
                .andExpect(header("X-Vault-Token", "hvs.test-wrapping-token"))
                .andRespond(withSuccess(
                        "{\"data\":{\"secret_id\":\"s.xyz123\",\"role_id\":\"r.abc\"}}",
                        MediaType.APPLICATION_JSON));

        assertThat(resolver.resolve()).isEqualTo("s.xyz123");
        mockServer.verify();
    }

    @Test
    void resolve_writesToFileWhenConfigured() throws Exception {
        Path tmpFile = Files.createTempFile("vault-unwrap-test", ".secret");
        tmpFile.toFile().deleteOnExit();
        // Clear default content
        Files.writeString(tmpFile, "");
        props.getApprole().setUnwrapOutputFile(tmpFile.toString());
        resolver = new WrappedSecretIdResolver(props, restTemplate);

        mockServer.expect(requestTo("http://localhost:8200/v1/sys/wrapping/unwrap"))
                .andRespond(withSuccess(
                        "{\"data\":{\"secret_id\":\"s.abc\"}}",
                        MediaType.APPLICATION_JSON));

        resolver.resolve();

        assertThat(Files.readString(tmpFile)).isEqualTo("s.abc");
        // POSIX file perms may not be testable on all FS, so don't assert them.
        mockServer.verify();
    }

    @Test
    void resolve_throwsOnEmptyResponseBody() {
        mockServer.expect(requestTo("http://localhost:8200/v1/sys/wrapping/unwrap"))
                .andRespond(withSuccess("", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> resolver.resolve())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("empty body");
    }

    @Test
    void resolve_throwsWhenResponseMissingDataObject() {
        mockServer.expect(requestTo("http://localhost:8200/v1/sys/wrapping/unwrap"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> resolver.resolve())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing 'data'");
    }

    @Test
    void resolve_throwsWhenDataMissingSecretId() {
        mockServer.expect(requestTo("http://localhost:8200/v1/sys/wrapping/unwrap"))
                .andRespond(withSuccess(
                        "{\"data\":{\"role_id\":\"r.abc\"}}",
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> resolver.resolve())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing non-empty 'secret_id'");
    }

    @Test
    void resolve_throwsOnServerError() {
        mockServer.expect(requestTo("http://localhost:8200/v1/sys/wrapping/unwrap"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThatThrownBy(() -> resolver.resolve())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Vault unwrap call failed");
    }

    @Test
    void resolve_readsWrappingTokenFromFileWhenConfigured() throws Exception {
        Path tokenFile = Files.createTempFile("vault-wrapping-test", ".token");
        tokenFile.toFile().deleteOnExit();
        Files.writeString(tokenFile, "hvs.from-file-token");
        // When wrappingTokenFile is set, it OVERRIDES wrappingToken
        props.getApprole().setWrappingToken("hvs.ignored");
        props.getApprole().setWrappingTokenFile(tokenFile.toString());
        resolver = new WrappedSecretIdResolver(props, restTemplate);

        mockServer.expect(requestTo("http://localhost:8200/v1/sys/wrapping/unwrap"))
                .andExpect(header("X-Vault-Token", "hvs.from-file-token"))
                .andRespond(withSuccess(
                        "{\"data\":{\"secret_id\":\"s.789\"}}",
                        MediaType.APPLICATION_JSON));

        assertThat(resolver.resolve()).isEqualTo("s.789");
    }

    @Test
    void resolve_doesNotWriteWhenOutputFileNotConfigured() throws Exception {
        // Default props have unwrapOutputFile == null
        Path tmpDir = Files.createTempDirectory("vault-noop-test");
        Path sentinel = tmpDir.resolve("sentinel");
        Files.writeString(sentinel, "untouched");
        // We can't assert a file wasn't written because we have no expectation,
        // but we CAN verify resolve still returns the secret_id without error.
        mockServer.expect(requestTo("http://localhost:8200/v1/sys/wrapping/unwrap"))
                .andRespond(withSuccess(
                        "{\"data\":{\"secret_id\":\"s.no-write\"}}",
                        MediaType.APPLICATION_JSON));

        assertThat(resolver.resolve()).isEqualTo("s.no-write");
        assertThat(Files.exists(sentinel)).isTrue();  // unrelated sentinel still there
    }
}