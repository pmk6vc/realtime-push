package testutils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import okhttp3.MediaType;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.*;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

import io.micronaut.context.ApplicationContext;
import io.micronaut.runtime.server.EmbeddedServer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class TestIntegrationWrapper {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final OkHttpClient http = new OkHttpClient.Builder()
            .callTimeout(Duration.ofSeconds(15))
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
        String adminToken = getAdminToken("admin", "admin");
        ensureRealm(adminToken);
        ensurePublicClient(adminToken);
        createUserWithPassword(adminToken, "alice", "alice!");
        createUserWithPassword(adminToken, "bob", "bob!");

        // --- Render Envoy config from your repo template and start Envoy ---
        Path renderedEnvoy = renderEnvoyConfig();

        envoy = new GenericContainer<>("envoyproxy/envoy:v1.32-latest")
                .withNetwork(network)
                .withExposedPorts(10000, 9901)
                .withCopyFileToContainer(MountableFile.forHostPath(renderedEnvoy), "/etc/envoy/envoy.yaml")
                // allow container to reach host JVM (Micronaut app)
                .withExtraHost("host.testcontainers.internal", "host-gateway")
                .withCommand("-c", "/etc/envoy/envoy.yaml", "--log-level", "info")
                .waitingFor(Wait.forHttp("/ready").forPort(9901).withStartupTimeout(Duration.ofMinutes(1)));
        envoy.start();

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

    // -------- Keycloak helpers --------

    private String getAdminToken(String username, String password) throws IOException {
        RequestBody body = new FormBody.Builder()
                .add("grant_type", "password")
                .add("client_id", "admin-cli")
                .add("username", username)
                .add("password", password)
                .build();

        Request req = new Request.Builder()
                .url(keycloakBaseUrl + "/realms/master/protocol/openid-connect/token")
                .post(body)
                .build();

        try (Response r = http.newCall(req).execute()) {
            assertEquals(200, r.code(), "admin token failed: " + (r.body() != null ? r.body().string() : ""));
            JsonNode json = MAPPER.readTree(Objects.requireNonNull(r.body()).string());
            return json.get("access_token").asText();
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
            assertEquals(200, r.code());
            JsonNode arr = MAPPER.readTree(Objects.requireNonNull(r.body()).string());
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
            if (r.code() == 409) {
                userId = lookupUserId(adminToken, username);
            } else {
                assertEquals(201, r.code(), "user create failed: " + r.code());
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
            assertEquals(204, r.code(), "set password failed: " + r.code());
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
            assertEquals(200, r.code());
            JsonNode arr = MAPPER.readTree(Objects.requireNonNull(r.body()).string());
            assertTrue(arr.isArray() && !arr.isEmpty());
            return arr.get(0).get("id").asText();
        }
    }

    private String passwordGrant(String username, String password) throws IOException {
        RequestBody body = new FormBody.Builder()
                .add("grant_type", "password")
                .add("client_id", CLIENT_ID)
                .add("username", username)
                .add("password", password)
                .build();

        Request req = new Request.Builder()
                .url(keycloakBaseUrl + "/realms/" + REALM + "/protocol/openid-connect/token")
                .post(body)
                .build();

        try (Response r = http.newCall(req).execute()) {
            assertEquals(200, r.code(), "token failed: " + (r.body() != null ? r.body().string() : ""));
            JsonNode json = MAPPER.readTree(Objects.requireNonNull(r.body()).string());
            return json.get("access_token").asText();
        }
    }

    // -------- Envoy config rendering --------

    private Path renderEnvoyConfig() throws IOException {
        Path templatePath = Paths.get("").toAbsolutePath().resolve("envoy").resolve("envoy.template.yaml").normalize();
        if (!Files.exists(templatePath)) {
            throw new IllegalStateException("Envoy template not found at: " + templatePath);
        }
        String tpl = Files.readString(templatePath, StandardCharsets.UTF_8);

        // Token issuer will match the base URL used to request tokens
        String issuer = keycloakBaseUrl + "/realms/" + REALM;

        // Envoy in docker network should fetch JWKS via service alias
        String jwksUri = "http://keycloak:8080/realms/" + REALM + "/protocol/openid-connect/certs";

        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("KC_ISSUER", issuer);
        vars.put("KC_JWKS_URI", jwksUri);
        vars.put("KC_JWKS_HOST", "keycloak");
        vars.put("KC_JWKS_PORT", "8080");

        vars.put("UPSTREAM_HOST", "host.testcontainers.internal");
        vars.put("UPSTREAM_PORT", Integer.toString(app.getPort()));

        String rendered = tpl;
        for (var e : vars.entrySet()) {
            rendered = rendered.replace("${" + e.getKey() + "}", e.getValue());
        }

        if (rendered.contains("${")) {
            throw new IllegalStateException("Unrendered placeholders remain in envoy config. Check template vars.");
        }

        Path out = Files.createTempFile("envoy-it-", ".yaml");
        Files.writeString(out, rendered, StandardCharsets.UTF_8);
        return out;
    }
}