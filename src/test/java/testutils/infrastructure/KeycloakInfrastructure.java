package testutils.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.HashMap;
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
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Keycloak infrastructure: database, server, realm, and user management. */
public class KeycloakInfrastructure extends ContainerInfrastructure {

  public static final String REALM = "chat";
  public static final String CLIENT_ID = "chat-frontend";

  private final OkHttpClient http;
  private final ObjectMapper mapper;

  private PostgreSQLContainer database;
  private GenericContainer<?> keycloak;

  private String baseUrl;
  private String adminToken;
  private final Map<String, String> userSubByUsername = new HashMap<>();

  public KeycloakInfrastructure(Network network, OkHttpClient http, ObjectMapper mapper) {
    super(network);
    this.http = http;
    this.mapper = mapper;
  }

  @Override
  public void start() throws Exception {
    // Start PostgreSQL for Keycloak
    database =
        new PostgreSQLContainer("postgres:16")
            .withNetwork(network)
            .withNetworkAliases("keycloak-db")
            .withDatabaseName("keycloak")
            .withUsername("keycloak")
            .withPassword("keycloak")
            .waitingFor(Wait.forListeningPort().withStartupTimeout(STARTUP_TIMEOUT));
    database.start();

    // Start Keycloak server
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

    baseUrl = "http://" + keycloak.getHost() + ":" + keycloak.getMappedPort(8080);

    // Configure realm and client
    adminToken = obtainAdminToken("admin", "admin");
    ensureRealm();
    ensurePublicClient();
  }

  @Override
  public void close() {
    if (keycloak != null) keycloak.stop();
    if (database != null) database.stop();
  }

  @Override
  public String getName() {
    return "Keycloak";
  }

  // -------------------------
  // Public API
  // -------------------------

  public String getBaseUrl() {
    return baseUrl;
  }

  public GenericContainer<?> getContainer() {
    return keycloak;
  }

  /**
   * Create a user with password. Returns the user's subject (sub) claim for use in tests.
   *
   * @return User ID (sub claim from JWT)
   */
  public String createUser(String username, String password) throws IOException {
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
            .url(baseUrl + "/admin/realms/" + REALM + "/users")
            .post(RequestBody.create(payload, MediaType.get("application/json")))
            .header("Authorization", "Bearer " + adminToken)
            .build();

    String userId;
    try (Response r = http.newCall(create).execute()) {
      String body = r.body() == null ? "" : r.body().string();
      if (r.code() == 409) {
        // User already exists, look up ID
        userId = lookupUserId(username);
      } else {
        assertEquals(201, r.code(), "user create failed: " + body);
        String location = r.header("Location");
        Assertions.assertNotNull(location, "Location header missing");
        userId = location.substring(location.lastIndexOf('/') + 1);
      }
    }

    // Set password
    String passPayload =
        "{\"type\":\"password\",\"value\":\"" + password + "\",\"temporary\":false}";
    Request setPass =
        new Request.Builder()
            .url(baseUrl + "/admin/realms/" + REALM + "/users/" + userId + "/reset-password")
            .put(RequestBody.create(passPayload, MediaType.get("application/json")))
            .header("Authorization", "Bearer " + adminToken)
            .build();

    try (Response r = http.newCall(setPass).execute()) {
      String body = r.body() == null ? "" : r.body().string();
      assertEquals(204, r.code(), "set password failed: " + body);
    }

    userSubByUsername.put(username, userId);
    return userId;
  }

  /**
   * Get user's subject (sub) claim by username.
   *
   * @throws IllegalArgumentException if user doesn't exist
   */
  public String getUserSub(String username) {
    String sub = userSubByUsername.get(username);
    if (sub == null) {
      throw new IllegalArgumentException("Unknown user: " + username);
    }
    return sub;
  }

  /**
   * Obtain JWT token via password grant for testing.
   *
   * @return Access token (JWT)
   */
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
            .url(baseUrl + "/realms/" + REALM + "/protocol/openid-connect/token")
            .post(body)
            .build();

    try (Response r = http.newCall(req).execute()) {
      String responseBody = r.body() == null ? "" : r.body().string();
      assertEquals(200, r.code(), "token failed: " + responseBody);

      JsonNode json = mapper.readTree(responseBody);
      JsonNode token = json.get("access_token");
      Assertions.assertNotNull(token, "access_token missing: " + responseBody);
      Assertions.assertFalse(token.asText().isBlank(), "access_token blank: " + responseBody);
      return token.asText();
    }
  }

  // -------------------------
  // Private helpers
  // -------------------------

  private String obtainAdminToken(String username, String password) throws IOException {
    RequestBody body =
        new FormBody.Builder()
            .add("grant_type", "password")
            .add("client_id", "admin-cli")
            .add("username", username)
            .add("password", password)
            .build();

    Request req =
        new Request.Builder()
            .url(baseUrl + "/realms/master/protocol/openid-connect/token")
            .post(body)
            .build();

    try (Response r = http.newCall(req).execute()) {
      String respBody = r.body() == null ? "" : r.body().string();
      if (r.code() != 200) {
        throw new IllegalStateException(
            "Admin token request failed: HTTP " + r.code() + " body=" + respBody);
      }
      JsonNode json = mapper.readTree(respBody);
      JsonNode token = json.get("access_token");
      if (token == null || token.asText().isBlank()) {
        throw new IllegalStateException("Admin token missing in response: " + respBody);
      }
      return token.asText();
    }
  }

  private void ensureRealm() throws IOException {
    Request get =
        new Request.Builder()
            .url(baseUrl + "/admin/realms/" + REALM)
            .get()
            .header("Authorization", "Bearer " + adminToken)
            .build();

    try (Response r = http.newCall(get).execute()) {
      if (r.code() == 200) return; // Realm exists
      if (r.code() != 404) Assertions.fail("Unexpected realm GET: " + r.code());
    }

    // Create realm
    String payload = "{\"realm\":\"" + REALM + "\",\"enabled\":true}";
    Request create =
        new Request.Builder()
            .url(baseUrl + "/admin/realms")
            .post(RequestBody.create(payload, MediaType.get("application/json")))
            .header("Authorization", "Bearer " + adminToken)
            .build();

    try (Response r = http.newCall(create).execute()) {
      Assertions.assertTrue(r.code() == 201 || r.code() == 204, "realm create failed: " + r.code());
    }
  }

  private void ensurePublicClient() throws IOException {
    HttpUrl url =
        Objects.requireNonNull(HttpUrl.parse(baseUrl + "/admin/realms/" + REALM + "/clients"))
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
      assertEquals(200, r.code(), "List clients failed: " + body);

      JsonNode arr = mapper.readTree(body);
      if (arr.isArray() && !arr.isEmpty()) return; // Client exists
    }

    // Create public client
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
            .url(baseUrl + "/admin/realms/" + REALM + "/clients")
            .post(RequestBody.create(payload, MediaType.get("application/json")))
            .header("Authorization", "Bearer " + adminToken)
            .build();

    try (Response r = http.newCall(create).execute()) {
      Assertions.assertTrue(
          r.code() == 201 || r.code() == 204, "client create failed: " + r.code());
    }
  }

  private String lookupUserId(String username) throws IOException {
    HttpUrl url =
        Objects.requireNonNull(HttpUrl.parse(baseUrl + "/admin/realms/" + REALM + "/users"))
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
      assertEquals(200, r.code(), "lookup user failed: " + body);

      JsonNode arr = mapper.readTree(body);
      Assertions.assertTrue(arr.isArray() && !arr.isEmpty(), "user not found: " + username);
      return arr.get(0).get("id").asText();
    }
  }
}
