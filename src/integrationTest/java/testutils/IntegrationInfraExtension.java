package testutils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.Closeable;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import okhttp3.FormBody;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

/**
 * JUnit 5 extension that starts all infra ONCE per test run, and tears it down ONCE after all
 * integration tests complete.
 */
public final class IntegrationInfraExtension implements BeforeAllCallback, ParameterResolver {

  // Tunables
  private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(20);
  private static final Duration STARTUP_TIMEOUT = Duration.ofMinutes(2);

  // Keycloak realm/client
  public static final String REALM = "chat";
  public static final String CLIENT_ID = "chat-frontend";

  private static final ObjectMapper MAPPER = new ObjectMapper();

  // Root store key
  private static final ExtensionContext.Namespace NS =
      ExtensionContext.Namespace.create(IntegrationInfraExtension.class);

  // Parameter resolution - allow injecting Infra into test methods
  @Override
  public boolean supportsParameter(
      ParameterContext parameterContext, ExtensionContext extensionContext)
      throws ParameterResolutionException {
    return parameterContext.getParameter().getType().equals(Infra.class);
  }

  @Override
  public Object resolveParameter(
      ParameterContext parameterContext, ExtensionContext extensionContext)
      throws ParameterResolutionException {
    return infra(extensionContext);
  }

  @Override
  public void beforeAll(ExtensionContext context) throws Exception {
    // Ensure initialization happens once for the entire engine run.
    // We store a Closeable resource in the ROOT store: it is closed exactly once at end.
    ExtensionContext root = context.getRoot();
    root.getStore(NS)
        .getOrComputeIfAbsent(
            "infra",
            k -> {
              try {
                return new Infra();
              } catch (Exception e) {
                throw new RuntimeException(e);
              }
            },
            Infra.class);
  }

  /** Access the shared infra from any test class. */
  public static Infra infra(ExtensionContext context) {
    return context.getRoot().getStore(NS).get("infra", Infra.class);
  }

  /**
   * Holds all shared infrastructure. Implements Closeable so JUnit will call close() exactly once
   * when the root context is closed (end of the whole integration test run).
   */
  public static final class Infra implements Closeable {

    private final OkHttpClient http = new OkHttpClient.Builder().callTimeout(HTTP_TIMEOUT).build();

    // Shared infra objects
    private Network network;

    private GenericContainer<?> messagingApp;
    private PostgreSQLContainer<?> kcDb;
    private GenericContainer<?> keycloak;
    private GenericContainer<?> envoy;

    private String keycloakBaseUrl;
    private String envoyBaseUrl;
    private String envoyAdminBaseUrl;

    private Infra() throws Exception {
      start();
    }

    // -------------------------
    // Public getters for tests
    // -------------------------

    public OkHttpClient http() {
      return http;
    }

    public ObjectMapper mapper() {
      return MAPPER;
    }

    public String keycloakBaseUrl() {
      return keycloakBaseUrl;
    }

    public String envoyBaseUrl() {
      return envoyBaseUrl;
    }

    public String envoyAdminBaseUrl() {
      return envoyAdminBaseUrl;
    }

    public GenericContainer<?> messagingAppContainer() {
      return messagingApp;
    }

    public GenericContainer<?> envoyContainer() {
      return envoy;
    }

    public GenericContainer<?> keycloakContainer() {
      return keycloak;
    }

    // -------------------------
    // Startup
    // -------------------------

    private void start() throws Exception {
      // --- Start network first ---
      network = Network.newNetwork();

      // --- Postgres for Keycloak ---
      kcDb =
          new PostgreSQLContainer<>("postgres:16")
              .withNetwork(network)
              .withNetworkAliases("keycloak-db")
              .withDatabaseName("keycloak")
              .withUsername("keycloak")
              .withPassword("keycloak")
              .waitingFor(Wait.forListeningPort().withStartupTimeout(STARTUP_TIMEOUT));
      kcDb.start();

      // --- Keycloak ---
      keycloak =
          new GenericContainer<>("quay.io/keycloak/keycloak:26.4.7")
              .withNetwork(network)
              .withNetworkAliases("keycloak")
              .withExposedPorts(8080)
              .withEnv(
                  Map.of(
                      "KC_DB", "postgres",
                      "KC_DB_URL", "jdbc:postgresql://keycloak-db:5432/keycloak",
                      "KC_DB_USERNAME", "keycloak",
                      "KC_DB_PASSWORD", "keycloak",
                      "KEYCLOAK_ADMIN", "admin",
                      "KEYCLOAK_ADMIN_PASSWORD", "admin"))
              .withCommand("start-dev", "--http-port=8080", "--hostname-strict=false")
              .waitingFor(Wait.forHttp("/").forPort(8080).withStartupTimeout(STARTUP_TIMEOUT));
      keycloak.start();
      keycloakBaseUrl = "http://" + keycloak.getHost() + ":" + keycloak.getMappedPort(8080);

      // --- Configure Keycloak (realm/client/users) ---
      String adminToken = getAdminToken("admin", "admin");
      ensureRealm(adminToken);
      ensurePublicClient(adminToken);
      createUserWithPassword(adminToken, "alice", "alice!");
      createUserWithPassword(adminToken, "bob", "bob!");

      // --- Micronaut app container ---
      messagingApp =
          new GenericContainer<>("realtime-messaging:it")
              .withNetwork(network)
              .withNetworkAliases("messaging_app")
              .withExposedPorts(8080)
              .withEnv("MICRONAUT_ENVIRONMENTS", "test")
              .withEnv("MICRONAUT_SERVER_HOST", "0.0.0.0")
              .withEnv("MICRONAUT_SERVER_PORT", "8080")
              .waitingFor(Wait.forListeningPort().withStartupTimeout(STARTUP_TIMEOUT));
      messagingApp.start();

      // --- Envoy ---
      String issuer = keycloakBaseUrl + "/realms/" + REALM;
      String jwksUri = "http://keycloak:8080/realms/" + REALM + "/protocol/openid-connect/certs";

      java.nio.file.Path projectRoot = java.nio.file.Paths.get("").toAbsolutePath().normalize();
      java.nio.file.Path envoyDir = projectRoot.resolve("envoy");

      envoy =
          new GenericContainer<>("realtime-envoy:it")
              .withNetwork(network)
              .withNetworkAliases("envoy")
              .withExposedPorts(10000, 9901)
              .withCopyFileToContainer(
                  MountableFile.forHostPath(envoyDir.resolve("envoy.template.yaml")),
                  "/etc/envoy/envoy.template.yaml")
              .withEnv("KC_ISSUER", issuer)
              .withEnv("KC_JWKS_URI", jwksUri)
              .withEnv("KC_JWKS_HOST", "keycloak")
              .withEnv("KC_JWKS_PORT", "8080")
              .withEnv("UPSTREAM_HOST", "messaging_app")
              .withEnv("UPSTREAM_PORT", "8080")
              .withEnv("ENVOY_LISTEN_PORT", "10000")
              .withEnv("ENVOY_ADMIN_PORT", "9901")
              .withStartupAttempts(1)
              .waitingFor(
                  Wait.forHttp("/server_info").forPort(9901).withStartupTimeout(STARTUP_TIMEOUT));

      envoy.start();
      envoyBaseUrl = "http://" + envoy.getHost() + ":" + envoy.getMappedPort(10000);
      envoyAdminBaseUrl = "http://" + envoy.getHost() + ":" + envoy.getMappedPort(9901);
    }

    // -------------------------
    // Helpers: tokens + admin API
    // -------------------------

    public String passwordGrant(String username, String password) throws IOException {
      RequestBody body =
          new FormBody.Builder()
              .add("grant_type", "password")
              .add("client_id", CLIENT_ID)
              .add("username", username)
              .add("password", password)
              .build();

      Request req =
          new Request.Builder()
              .url(keycloakBaseUrl + "/realms/" + REALM + "/protocol/openid-connect/token")
              .post(body)
              .build();

      try (Response r = http.newCall(req).execute()) {
        String responseBody = r.body() == null ? "" : r.body().string();
        Assertions.assertEquals(200, r.code(), "token failed: " + responseBody);

        JsonNode json = MAPPER.readTree(responseBody);
        JsonNode token = json.get("access_token");
        Assertions.assertNotNull(token, "access_token missing: " + responseBody);
        Assertions.assertTrue(!token.asText().isBlank(), "access_token blank: " + responseBody);
        return token.asText();
      }
    }

    private String getAdminToken(String username, String password) throws IOException {
      RequestBody body =
          new FormBody.Builder()
              .add("grant_type", "password")
              .add("client_id", "admin-cli")
              .add("username", username)
              .add("password", password)
              .build();

      Request req =
          new Request.Builder()
              .url(keycloakBaseUrl + "/realms/master/protocol/openid-connect/token")
              .post(body)
              .build();

      try (Response r = http.newCall(req).execute()) {
        String respBody = r.body() == null ? "" : r.body().string();
        if (r.code() != 200) {
          throw new IllegalStateException(
              "Admin token request failed: HTTP " + r.code() + " body=" + respBody);
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
      Request get =
          new Request.Builder()
              .url(keycloakBaseUrl + "/admin/realms/" + REALM)
              .get()
              .header("Authorization", "Bearer " + adminToken)
              .build();

      try (Response r = http.newCall(get).execute()) {
        if (r.code() == 200) return;
        if (r.code() != 404) Assertions.fail("Unexpected realm GET: " + r.code());
      }

      String payload = "{\"realm\":\"" + REALM + "\",\"enabled\":true}";
      Request create =
          new Request.Builder()
              .url(keycloakBaseUrl + "/admin/realms")
              .post(RequestBody.create(payload, MediaType.get("application/json")))
              .header("Authorization", "Bearer " + adminToken)
              .build();

      try (Response r = http.newCall(create).execute()) {
        Assertions.assertTrue(
            r.code() == 201 || r.code() == 204, "realm create failed: " + r.code());
      }
    }

    private void ensurePublicClient(String adminToken) throws IOException {
      HttpUrl url =
          Objects.requireNonNull(
                  HttpUrl.parse(keycloakBaseUrl + "/admin/realms/" + REALM + "/clients"))
              .newBuilder()
              .addQueryParameter("clientId", CLIENT_ID)
              .build();

      Request list =
          new Request.Builder()
              .url(url)
              .get()
              .header("Authorization", "Bearer " + adminToken)
              .build();

      try (Response r = http.newCall(list).execute()) {
        String body = r.body() == null ? "" : r.body().string();
        Assertions.assertEquals(200, r.code(), "List clients failed: " + body);

        JsonNode arr = MAPPER.readTree(body);
        if (arr.isArray() && !arr.isEmpty()) return;
      }

      String payload =
          """
                    {
                      "clientId": "%s",
                      "enabled": true,
                      "publicClient": true,
                      "standardFlowEnabled": true,
                      "directAccessGrantsEnabled": true,
                      "redirectUris": ["http://localhost/*"],
                      "webOrigins": ["*"]
                    }
                    """
              .formatted(CLIENT_ID);

      Request create =
          new Request.Builder()
              .url(keycloakBaseUrl + "/admin/realms/" + REALM + "/clients")
              .post(RequestBody.create(payload, MediaType.get("application/json")))
              .header("Authorization", "Bearer " + adminToken)
              .build();

      try (Response r = http.newCall(create).execute()) {
        Assertions.assertTrue(
            r.code() == 201 || r.code() == 204, "client create failed: " + r.code());
      }
    }

    private void createUserWithPassword(String adminToken, String username, String password)
        throws IOException {
      String payload =
          """
              {
                "username": "%s",
                "email": "%s@example.com",
                "emailVerified": true,
                "firstName": "Test",
                "lastName": "User",
                "enabled": true,
                "requiredActions": []
              }
              """
              .formatted(username, username);
      Request create =
          new Request.Builder()
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
          Assertions.assertEquals(201, r.code(), "user create failed: " + body);
          String loc = r.header("Location");
          Assertions.assertNotNull(loc);
          userId = loc.substring(loc.lastIndexOf('/') + 1);
        }
      }

      String passPayload =
          "{\"type\":\"password\",\"value\":\"" + password + "\",\"temporary\":false}";
      Request setPass =
          new Request.Builder()
              .url(
                  keycloakBaseUrl
                      + "/admin/realms/"
                      + REALM
                      + "/users/"
                      + userId
                      + "/reset-password")
              .put(RequestBody.create(passPayload, MediaType.get("application/json")))
              .header("Authorization", "Bearer " + adminToken)
              .build();

      try (Response r = http.newCall(setPass).execute()) {
        String body = r.body() == null ? "" : r.body().string();
        Assertions.assertEquals(204, r.code(), "set password failed: " + body);
      }
    }

    private String lookupUserId(String adminToken, String username) throws IOException {
      HttpUrl url =
          Objects.requireNonNull(
                  HttpUrl.parse(keycloakBaseUrl + "/admin/realms/" + REALM + "/users"))
              .newBuilder()
              .addQueryParameter("username", username)
              .addQueryParameter("exact", "true")
              .build();

      Request req =
          new Request.Builder()
              .url(url)
              .get()
              .header("Authorization", "Bearer " + adminToken)
              .build();

      try (Response r = http.newCall(req).execute()) {
        String body = r.body() == null ? "" : r.body().string();
        Assertions.assertEquals(200, r.code(), "lookup user failed: " + body);

        JsonNode arr = MAPPER.readTree(body);
        Assertions.assertTrue(arr.isArray() && !arr.isEmpty(), "user not found: " + username);
        return arr.get(0).get("id").asText();
      }
    }

    public String envoyClusters() throws IOException {
      Request req =
          new Request.Builder().url(envoyAdminBaseUrl + "/clusters?format=json").get().build();

      try (Response r = http.newCall(req).execute()) {
        String body = r.body() == null ? "" : r.body().string();
        Assertions.assertEquals(200, r.code(), "envoy admin /clusters failed: " + body);
        return body;
      }
    }

    // -------------------------
    // Teardown (called once)
    // -------------------------

    @Override
    public void close() {
      // Called exactly once when JUnit closes the ROOT context (end of test run)
      if (envoy != null) envoy.stop();
      if (keycloak != null) keycloak.stop();
      if (kcDb != null) kcDb.stop();
      if (network != null) network.close();
      if (messagingApp != null) messagingApp.stop();
    }
  }
}
