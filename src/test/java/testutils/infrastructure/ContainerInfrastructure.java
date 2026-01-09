package testutils.infrastructure;

import java.io.Closeable;
import java.time.Duration;
import org.testcontainers.containers.Network;

/**
 * Abstract base class for infrastructure components. Provides common lifecycle and configuration.
 */
public abstract class ContainerInfrastructure implements Closeable {

  protected static final Duration STARTUP_TIMEOUT = Duration.ofMinutes(2);
  protected final Network network;

  protected ContainerInfrastructure(Network network) {
    this.network = network;
  }

  /** Start the infrastructure component. Called during initialization. */
  public abstract void start() throws Exception;

  /** Stop the infrastructure component. Called during cleanup. */
  @Override
  public abstract void close();

  /** Get a human-readable name for logging. */
  public abstract String getName();
}
