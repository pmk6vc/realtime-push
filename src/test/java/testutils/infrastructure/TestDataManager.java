package testutils.infrastructure;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.UUID;

/** Manages test data seeding and cleanup for integration tests. */
public class TestDataManager {

  private final CitusInfrastructure citus;
  private final KeycloakInfrastructure keycloak;

  public TestDataManager(CitusInfrastructure citus, KeycloakInfrastructure keycloak) {
    this.citus = citus;
    this.keycloak = keycloak;
  }

  /**
   * Seeds a user in both Keycloak and the Citus database.
   *
   * @param username Username for the user
   * @param password Password for the user
   * @return User's subject (sub) claim from Keycloak
   */
  public String seedUser(String username, String password) throws Exception {
    String userId = keycloak.createUser(username, password);
    insertUserInDatabase(userId);
    return userId;
  }

  /**
   * Seeds a channel in the Citus database.
   *
   * @param channelId Channel ID (UUID string)
   * @param channelName Channel name
   */
  public void seedChannel(String channelId, String channelName) throws Exception {
    try (Connection conn = getCitusConnection();
        PreparedStatement stmt =
            conn.prepareStatement(
                "INSERT INTO channels (channel_id, channel_name) VALUES (?, ?) ON CONFLICT DO NOTHING")) {
      stmt.setObject(1, UUID.fromString(channelId));
      stmt.setString(2, channelName);
      stmt.executeUpdate();
    }
  }

  /**
   * Inserts a user into the Citus database.
   *
   * @param userId User ID (UUID string)
   */
  public void insertUserInDatabase(String userId) throws Exception {
    try (Connection conn = getCitusConnection();
        PreparedStatement stmt =
            conn.prepareStatement(
                "INSERT INTO users (user_id) VALUES (?) ON CONFLICT DO NOTHING")) {
      stmt.setObject(1, UUID.fromString(userId));
      stmt.executeUpdate();
    }
  }

  /**
   * Deletes all messages from a channel (useful for test cleanup).
   *
   * @param channelId Channel ID (UUID string)
   */
  public void deleteMessagesFromChannel(String channelId) throws Exception {
    try (Connection conn = getCitusConnection();
        PreparedStatement stmt =
            conn.prepareStatement("DELETE FROM messages WHERE channel_id = ?")) {
      stmt.setObject(1, UUID.fromString(channelId));
      stmt.executeUpdate();
    }
  }

  /**
   * Gets a JDBC connection to the Citus master node.
   *
   * @return JDBC connection
   */
  public Connection getCitusConnection() throws Exception {
    String host = citus.getMaster().getHost();
    Integer port = citus.getMaster().getMappedPort(5432);
    String jdbcUrl = String.format("jdbc:postgresql://%s:%d/%s", host, port, citus.getDatabase());
    return DriverManager.getConnection(jdbcUrl, citus.getUsername(), citus.getPassword());
  }
}
