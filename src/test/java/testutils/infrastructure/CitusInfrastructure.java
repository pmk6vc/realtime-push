package testutils.infrastructure;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

/** Citus distributed PostgreSQL infrastructure: coordinator (master) and worker nodes. */
public class CitusInfrastructure extends ContainerInfrastructure {

  private static final int WORKER_COUNT = 3;
  private static final String IMAGE = "citusdata/citus:postgres_16";

  private final String username;
  private final String password;
  private final String database;

  private GenericContainer<?> master;
  private List<GenericContainer<?>> workers;

  public CitusInfrastructure(Network network, String username, String password, String database) {
    super(network);
    this.username = username;
    this.password = password;
    this.database = database;
  }

  @Override
  public void start() throws Exception {
    Path projectRoot = Paths.get("").toAbsolutePath().normalize();
    Path dbInitRunner = projectRoot.resolve("db/init-runner");
    Path dbInitCommon = projectRoot.resolve("db/init-common");
    Path dbInitMaster = projectRoot.resolve("db/init-master");

    // Start workers first
    workers = new ArrayList<>();
    for (int i = 1; i <= WORKER_COUNT; i++) {
      GenericContainer<?> worker =
          createCitusNode(
              "citus_worker_" + i, username, password, database, dbInitRunner, dbInitCommon);
      worker.start();
      workers.add(worker);
    }

    // Start master (coordinator) - depends on workers being ready
    master =
        createCitusNode("citus_master", username, password, database, dbInitRunner, dbInitCommon);
    // Copy master-specific init scripts
    master.withCopyFileToContainer(MountableFile.forHostPath(dbInitMaster), "/init-master");
    master.start();
  }

  @Override
  public void close() {
    // Stop master first, then workers
    if (master != null) master.stop();
    if (workers != null) {
      workers.forEach(GenericContainer::stop);
    }
  }

  @Override
  public String getName() {
    return "Citus";
  }

  public GenericContainer<?> getMaster() {
    return master;
  }

  public List<GenericContainer<?>> getWorkers() {
    return workers;
  }

  public String getUsername() {
    return username;
  }

  public String getPassword() {
    return password;
  }

  public String getDatabase() {
    return database;
  }

  private GenericContainer<?> createCitusNode(
      String nodeName, String user, String pass, String db, Path dbInitRunner, Path dbInitCommon) {
    return new GenericContainer<>(IMAGE)
        .withNetwork(network)
        .withNetworkAliases(nodeName)
        .withExposedPorts(5432)
        .withEnv("POSTGRES_USER", user)
        .withEnv("POSTGRES_PASSWORD", pass)
        .withEnv("POSTGRES_DB", db)
        .withCopyFileToContainer(
            MountableFile.forHostPath(dbInitRunner), "/docker-entrypoint-initdb.d")
        .withCopyFileToContainer(MountableFile.forHostPath(dbInitCommon), "/init-common")
        .waitingFor(Wait.forListeningPort().withStartupTimeout(STARTUP_TIMEOUT));
  }
}
