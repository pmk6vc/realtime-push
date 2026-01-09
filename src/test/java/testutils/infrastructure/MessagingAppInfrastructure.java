package testutils.infrastructure;

import java.time.Duration;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;

/** Micronaut messaging application infrastructure. */
public class MessagingAppInfrastructure extends ContainerInfrastructure {

  private static final String IMAGE = "realtime-messaging:it";
  private static final String NETWORK_ALIAS = "messaging_app";

  private final CitusInfrastructure citus;

  private GenericContainer<?> app;

  public MessagingAppInfrastructure(Network network, CitusInfrastructure citus) {
    super(network);
    this.citus = citus;
  }

  @Override
  public void start() throws Exception {
    app =
        new GenericContainer<>(IMAGE)
            .withNetwork(network)
            .withNetworkAliases(NETWORK_ALIAS)
            .withExposedPorts(8080)
            .withEnv("MICRONAUT_ENVIRONMENTS", "dev")
            .withEnv("MICRONAUT_SERVER_HOST", "0.0.0.0")
            .withEnv("MICRONAUT_SERVER_PORT", "8080")
            .withEnv("CITUS_USER", citus.getUsername())
            .withEnv("CITUS_PASSWORD", citus.getPassword())
            .withEnv("CITUS_DB", citus.getDatabase())
            .dependsOn(citus.getMaster())
            .waitingFor(
                Wait.forHttp("/health").forPort(8080).withStartupTimeout(Duration.ofMinutes(2)));
    app.start();
  }

  @Override
  public void close() {
    if (app != null) app.stop();
  }

  @Override
  public String getName() {
    return "MessagingApp";
  }

  public GenericContainer<?> getContainer() {
    return app;
  }

  public String getInternalHost() {
    return NETWORK_ALIAS;
  }

  public int getInternalPort() {
    return 8080;
  }
}
