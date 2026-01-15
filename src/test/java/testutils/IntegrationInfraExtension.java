package testutils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.Closeable;
import java.io.IOException;
import java.time.Duration;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import testutils.infrastructure.CitusInfrastructure;
import testutils.infrastructure.EnvoyInfrastructure;
import testutils.infrastructure.KeycloakInfrastructure;
import testutils.infrastructure.MessagingAppInfrastructure;
import testutils.infrastructure.TestDataManager;

/**
 * JUnit 5 extension that starts all infra ONCE per test run, and tears it down ONCE after all
 * integration tests complete.
 */
public final class IntegrationInfraExtension implements BeforeAllCallback, ParameterResolver {

  // Test channel - seeded in database for all tests
  public static final String TEST_CHANNEL_ID = "00000000-0000-0000-0000-000000000001";

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

    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(20);

    private final OkHttpClient http = new OkHttpClient.Builder().callTimeout(HTTP_TIMEOUT).build();

    // Infrastructure components
    private Network network;
    private KeycloakInfrastructure keycloak;
    private CitusInfrastructure citus;
    private MessagingAppInfrastructure messagingApp;
    private EnvoyInfrastructure envoy;
    private TestDataManager testData;

    private Infra() throws Exception {
      start();
    }

    // -------------------------
    // Startup
    // -------------------------

    private void start() throws Exception {
      // Start network first
      network = Network.newNetwork();

      // Start infrastructure components in order
      keycloak = new KeycloakInfrastructure(network, http, MAPPER);
      System.out.println("Starting " + keycloak.getName() + "...");
      keycloak.start();

      citus = new CitusInfrastructure(network, "citus", "citus", "citus");
      System.out.println("Starting " + citus.getName() + "...");
      citus.start();

      messagingApp = new MessagingAppInfrastructure(network, citus);
      System.out.println("Starting " + messagingApp.getName() + "...");
      messagingApp.start();

      // Create test data manager
      testData = new TestDataManager(citus, keycloak);

      // Seed test data
      System.out.println("Seeding test data...");
      String aliceId = testData.seedUser("alice", "alice!");
      String bobId = testData.seedUser("bob", "bob!");
      testData.seedChannel(TEST_CHANNEL_ID, "test-channel", aliceId);
      // Add bob as member of test channel so both users can use it
      testData.addChannelMember(TEST_CHANNEL_ID, bobId);

      envoy = new EnvoyInfrastructure(network, keycloak, messagingApp);
      System.out.println("Starting " + envoy.getName() + "...");
      envoy.start();

      System.out.println("All infrastructure started successfully!");
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
      return keycloak.getBaseUrl();
    }

    public String envoyBaseUrl() {
      return envoy.getBaseUrl();
    }

    public String envoyAdminBaseUrl() {
      return envoy.getAdminBaseUrl();
    }

    public GenericContainer<?> messagingAppContainer() {
      return messagingApp.getContainer();
    }

    public GenericContainer<?> envoyContainer() {
      return envoy.getContainer();
    }

    public GenericContainer<?> keycloakContainer() {
      return keycloak.getContainer();
    }

    public GenericContainer<?> citusMasterContainer() {
      return citus.getMaster();
    }

    public java.util.List<GenericContainer<?>> citusWorkerContainers() {
      return citus.getWorkers();
    }

    // -------------------------
    // Test data access
    // -------------------------

    public TestDataManager testDataManager() {
      return testData;
    }

    // -------------------------
    // User and authentication helpers
    // -------------------------

    public String userSub(String username) {
      return keycloak.getUserSub(username);
    }

    public String passwordGrant(String username, String password) throws IOException {
      return keycloak.passwordGrant(username, password);
    }

    // -------------------------
    // Utility methods
    // -------------------------

    public JsonNode readJsonBody(String body) throws IOException {
      String s = body == null ? "" : body.trim();
      if (!(s.startsWith("{") || s.startsWith("["))) {
        throw new IllegalArgumentException("Not JSON: " + s);
      }
      return MAPPER.readTree(s);
    }

    public java.net.URI envoyBaseUri() {
      return java.net.URI.create(envoyBaseUrl());
    }

    public String envoyClusters() throws IOException {
      Request req =
          new Request.Builder()
              .url(envoy.getAdminBaseUrl() + "/clusters?format=json")
              .get()
              .build();

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
      System.out.println("Stopping infrastructure...");

      if (envoy != null) envoy.close();
      if (messagingApp != null) messagingApp.close();
      if (keycloak != null) keycloak.close();
      if (citus != null) citus.close();
      if (network != null) network.close();

      System.out.println("All infrastructure stopped.");
    }
  }
}
