package testutils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micronaut.context.ApplicationContext;
import io.micronaut.runtime.server.EmbeddedServer;
import okhttp3.*;
import okhttp3.MediaType;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.utility.MountableFile;

import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class TestIntegrationWrapper {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final OkHttpClient http = new OkHttpClient.Builder()
            .callTimeout(Duration.ofSeconds(20))
            .build();

    private Network network;

    private ApplicationContext ctx;
    private EmbeddedServer app;

    private PostgreSQLContainer<?> kcDb;
    private GenericContainer<?> keycloak;
    private GenericContainer<?> envoy;

    private String keycloakBaseUrl;
    private String envoyBaseUrl;

    private static final String REALM = "chat";
    private static final String CLIENT_ID = "chat-frontend";

    @BeforeAll
    void startAll() throws Exception {
        // --- Start Micronaut in-process on random port ---
        ctx = ApplicationContext.builder()
                .environments("test")
                .properties(Map.of("micronaut.server.port", -1))
                .start();
        app = ctx.getBean(EmbeddedServer.class).start();

        // --- Start Keycloak + Postgres ---
        network = Network.newNetwork();

        kcDb = new PostgreSQLContainer<>("postgres:16")
                .withNetwork(network)
                .withNetworkAliases("keycloak-db")
                .withDatabaseName("keycloak")
                .withUsername("keycloak")
                .withPassword("keycloak")
                .waitingFor(Wait.forListeningPort());
        kcDb.start();

        keycloak = new GenericContainer<>("quay.io/keycloak/keycloak:26.4.7")
                .withNetwork(network)
                .withNetworkAliases("keycloak")
                .withExposedPorts(8080)
                .withEnv(Map.of(
                        "KC_DB", "postgres",
                        "KC_DB_URL", "jdbc:postgresql://keycloak-db:5432/keycloak",
                        "KC_DB_USERNAME", "keycloak",
                        "KC_DB_PASSWORD", "keycloak",
                        "KEYCLOAK_ADMIN", "admin",
                        "KEYCLOAK_ADMIN_PASSWORD", "admin"
                ))
                .withCommand("start-dev", "--http-port=8080", "--hostname-strict=false")
                .waitingFor(Wait.forHttp("/").forPort(8080).withStartupTimeout(Duration.ofMinutes(2)));

        keycloak.start();
        keycloakBaseUrl = "http://" + keycloak.getHost() + ":" + keycloak.getMappedPort(8080);

        // --- Configure Keycloak via Admin API (realm + client + user) ---
        String adminToken = getAdminToken(keycloakBaseUrl, "admin", "admin");
        ensureRealm(adminToken);
        ensurePublicClient(adminToken);
        createUserWithPassword(adminToken, "alice", "alice!");
        createUserWithPassword(adminToken, "bob", "bob!");

        // --- Start Envoy ---
        String issuer = keycloakBaseUrl + "/realms/" + REALM;
        String jwksUri = "http://keycloak:8080/realms/" + REALM + "/protocol/openid-connect/certs";
        Path projectRoot = Paths.get("").toAbsolutePath().normalize();
        Path envoyDir = projectRoot.resolve("envoy");
        ImageFromDockerfile envoyImage = new ImageFromDockerfile()
                .withFileFromPath(".", envoyDir)
                .withDockerfile(envoyDir.resolve("envoy.dockerfile"));
        envoy = new GenericContainer<>(envoyImage)
                .withNetwork(network)
                .withNetworkAliases("envoy")
                .withExposedPorts(10000, 9901)
                .withExtraHost("host.testcontainers.internal", "host-gateway")
                .withCopyFileToContainer(
                        MountableFile.forHostPath(envoyDir.resolve("envoy.template.yaml")),
                        "/etc/envoy/envoy.template.yaml"
                )
                .withEnv("KC_ISSUER", issuer)
                .withEnv("KC_JWKS_URI", jwksUri)
                .withEnv("KC_JWKS_HOST", "keycloak")
                .withEnv("KC_JWKS_PORT", "8080")
                .withEnv("UPSTREAM_HOST", "host.testcontainers.internal")
                .withEnv("UPSTREAM_PORT", Integer.toString(app.getPort()))
                .withEnv("ENVOY_LISTEN_PORT", "10000")
                .withEnv("ENVOY_ADMIN_PORT", "9901")
                .withStartupAttempts(1)
                .waitingFor(Wait.forHttp("/server_info").forPort(9901).withStartupTimeout(Duration.ofMinutes(2)));
        try {
            envoy.start();
        } catch (Exception e) {
            System.err.println("=== Envoy failed to start ===");
            if (envoy != null) {
                System.err.println(envoy.getLogs());
            }
            throw e;
        }
        envoyBaseUrl = "http://" + envoy.getHost() + ":" + envoy.getMappedPort(10000);
    }

    @AfterAll
    void stopAll() {
        if (envoy != null) envoy.stop();
        if (keycloak != null) keycloak.stop();
        if (kcDb != null) kcDb.stop();
        if (network != null) network.close();
        if (app != null) app.stop();
        if (ctx != null) ctx.close();
    }

    // -------------------------
    // Keycloak helpers
    // -------------------------

    private String getAdminToken(String baseUrl, String username, String password) throws IOException {
        RequestBody body = new FormBody.Builder()
                .add("grant_type", "password")
                .add("client_id", "admin-cli")
                .add("username", username)
                .add("password", password)
                .build();

        Request req = new Request.Builder()
                .url(baseUrl + "/realms/master/protocol/openid-connect/token")
                .post(body)
                .build();

        try (Response r = http.newCall(req).execute()) {
            String respBody = r.body() == null ? "" : r.body().string();
            if (r.code() != 200) {
                throw new IllegalStateException("Admin token request failed: HTTP " + r.code() + " body=" + respBody);
            }
            JsonNode json = MAPPER.readTree(respBody);
            JsonNode token = json.get("access_token");
            if (token == null || token.asText().isBlank()) {
                throw new IllegalStateException("Admin token missing in response: " + respBody);
            }
            return token.asText();
        }
    }

    private void ensureRealm(String adminToken) throws IOException {
        Request get = new Request.Builder()
                .url(keycloakBaseUrl + "/admin/realms/" + REALM)
                .get()
                .header("Authorization", "Bearer " + adminToken)
                .build();

        try (Response r = http.newCall(get).execute()) {
            if (r.code() == 200) return;
            if (r.code() != 404) fail("Unexpected realm GET: " + r.code());
        }

        String payload = "{\"realm\":\"" + REALM + "\",\"enabled\":true}";
        Request create = new Request.Builder()
                .url(keycloakBaseUrl + "/admin/realms")
                .post(RequestBody.create(payload, MediaType.get("application/json")))
                .header("Authorization", "Bearer " + adminToken)
                .build();

        try (Response r = http.newCall(create).execute()) {
            assertTrue(r.code() == 201 || r.code() == 204, "realm create failed: " + r.code());
        }
    }

    private void ensurePublicClient(String adminToken) throws IOException {
        HttpUrl url = Objects.requireNonNull(HttpUrl.parse(keycloakBaseUrl + "/admin/realms/" + REALM + "/clients"))
                .newBuilder()
                .addQueryParameter("clientId", CLIENT_ID)
                .build();

        Request list = new Request.Builder()
                .url(url)
                .get()
                .header("Authorization", "Bearer " + adminToken)
                .build();

        try (Response r = http.newCall(list).execute()) {
            String body = r.body() == null ? "" : r.body().string();
            assertEquals(200, r.code(), "List clients failed: " + body);

            JsonNode arr = MAPPER.readTree(body);
            if (arr.isArray() && !arr.isEmpty()) return;
        }

        String payload = """
          {
            "clientId": "%s",
            "enabled": true,
            "publicClient": true,
            "standardFlowEnabled": true,
            "directAccessGrantsEnabled": true,
            "redirectUris": ["http://localhost/*"],
            "webOrigins": ["*"]
          }
          """.formatted(CLIENT_ID);

        Request create = new Request.Builder()
                .url(keycloakBaseUrl + "/admin/realms/" + REALM + "/clients")
                .post(RequestBody.create(payload, MediaType.get("application/json")))
                .header("Authorization", "Bearer " + adminToken)
                .build();

        try (Response r = http.newCall(create).execute()) {
            assertTrue(r.code() == 201 || r.code() == 204, "client create failed: " + r.code());
        }
    }

    private void createUserWithPassword(String adminToken, String username, String password) throws IOException {
        String payload = "{\"username\":\"" + username + "\",\"enabled\":true}";
        Request create = new Request.Builder()
                .url(keycloakBaseUrl + "/admin/realms/" + REALM + "/users")
                .post(RequestBody.create(payload, MediaType.get("application/json")))
                .header("Authorization", "Bearer " + adminToken)
                .build();

        String userId;
        try (Response r = http.newCall(create).execute()) {
            String body = r.body() == null ? "" : r.body().string();
            if (r.code() == 409) {
                userId = lookupUserId(adminToken, username);
            } else {
                assertEquals(201, r.code(), "user create failed: " + body);
                String loc = r.header("Location");
                assertNotNull(loc);
                userId = loc.substring(loc.lastIndexOf('/') + 1);
            }
        }

        String passPayload = "{\"type\":\"password\",\"value\":\"" + password + "\",\"temporary\":false}";
        Request setPass = new Request.Builder()
                .url(keycloakBaseUrl + "/admin/realms/" + REALM + "/users/" + userId + "/reset-password")
                .put(RequestBody.create(passPayload, MediaType.get("application/json")))
                .header("Authorization", "Bearer " + adminToken)
                .build();

        try (Response r = http.newCall(setPass).execute()) {
            String body = r.body() == null ? "" : r.body().string();
            assertEquals(204, r.code(), "set password failed: " + body);
        }
    }

    private String lookupUserId(String adminToken, String username) throws IOException {
        HttpUrl url = Objects.requireNonNull(HttpUrl.parse(keycloakBaseUrl + "/admin/realms/" + REALM + "/users"))
                .newBuilder()
                .addQueryParameter("username", username)
                .addQueryParameter("exact", "true")
                .build();

        Request req = new Request.Builder()
                .url(url)
                .get()
                .header("Authorization", "Bearer " + adminToken)
                .build();

        try (Response r = http.newCall(req).execute()) {
            String body = r.body() == null ? "" : r.body().string();
            assertEquals(200, r.code(), "lookup user failed: " + body);

            JsonNode arr = MAPPER.readTree(body);
            assertTrue(arr.isArray() && !arr.isEmpty(), "user not found: " + username);
            return arr.get(0).get("id").asText();
        }
    }

    // -------------------------
    // Tests
    // -------------------------
    @Test
    void missingToken_is401() throws Exception {
        Request req = new Request.Builder()
                .url(envoyBaseUrl + "/__test/whoami")
                .get()
                .build();

        try (Response r = http.newCall(req).execute()) {
            assertEquals(401, r.code());
        }
    }
}
