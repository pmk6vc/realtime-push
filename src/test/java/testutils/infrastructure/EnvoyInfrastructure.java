package testutils.infrastructure;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

/** Envoy proxy infrastructure for authentication and load balancing. */
public class EnvoyInfrastructure extends ContainerInfrastructure {

  private static final String IMAGE = "realtime-envoy:it";
  private static final String NETWORK_ALIAS = "envoy";
  private static final int LISTENER_PORT = 10000;
  private static final int ADMIN_PORT = 9901;

  private final KeycloakInfrastructure keycloak;
  private final MessagingAppInfrastructure app;

  private GenericContainer<?> envoy;
  private String baseUrl;
  private String adminBaseUrl;

  public EnvoyInfrastructure(
      Network network, KeycloakInfrastructure keycloak, MessagingAppInfrastructure app) {
    super(network);
    this.keycloak = keycloak;
    this.app = app;
  }

  @Override
  public void start() throws Exception {
    String issuer = keycloak.getBaseUrl() + "/realms/" + KeycloakInfrastructure.REALM;
    String jwksUri =
        "http://keycloak:8080/realms/"
            + KeycloakInfrastructure.REALM
            + "/protocol/openid-connect/certs";

    Path projectRoot = Paths.get("").toAbsolutePath().normalize();
    Path envoyDir = projectRoot.resolve("envoy");

    envoy =
        new GenericContainer<>(IMAGE)
            .withNetwork(network)
            .withNetworkAliases(NETWORK_ALIAS)
            .withExposedPorts(LISTENER_PORT, ADMIN_PORT)
            .withCopyFileToContainer(
                MountableFile.forHostPath(envoyDir.resolve("envoy.template.yaml")),
                "/etc/envoy/envoy.template.yaml")
            .withEnv(
                Map.of(
                    "KC_ISSUER",
                    issuer,
                    "KC_JWKS_URI",
                    jwksUri,
                    "KC_JWKS_HOST",
                    "keycloak",
                    "KC_JWKS_PORT",
                    "8080",
                    "UPSTREAM_HOST",
                    app.getInternalHost(),
                    "UPSTREAM_PORT",
                    String.valueOf(app.getInternalPort()),
                    "ENVOY_LISTEN_PORT",
                    String.valueOf(LISTENER_PORT),
                    "ENVOY_ADMIN_PORT",
                    String.valueOf(ADMIN_PORT)))
            .withStartupAttempts(1)
            .waitingFor(
                Wait.forHttp("/server_info")
                    .forPort(ADMIN_PORT)
                    .withStartupTimeout(STARTUP_TIMEOUT));

    envoy.start();
    baseUrl = "http://" + envoy.getHost() + ":" + envoy.getMappedPort(LISTENER_PORT);
    adminBaseUrl = "http://" + envoy.getHost() + ":" + envoy.getMappedPort(ADMIN_PORT);
  }

  @Override
  public void close() {
    if (envoy != null) envoy.stop();
  }

  @Override
  public String getName() {
    return "Envoy";
  }

  public GenericContainer<?> getContainer() {
    return envoy;
  }

  public String getBaseUrl() {
    return baseUrl;
  }

  public String getAdminBaseUrl() {
    return adminBaseUrl;
  }
}
