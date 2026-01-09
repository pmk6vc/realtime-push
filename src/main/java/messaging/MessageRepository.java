package messaging;

import jakarta.inject.Singleton;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import javax.sql.DataSource;

@Singleton
public class MessageRepository {

  private final DataSource dataSource;

  public MessageRepository(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  public void insert(Message message) {
    String sql =
        "INSERT INTO messages (channel_id, message_id, sender_user_id, body) "
            + "VALUES (?, ?, ?, ?)";

    try (Connection conn = dataSource.getConnection();
        PreparedStatement stmt = conn.prepareStatement(sql)) {
      stmt.setObject(1, message.channelId());
      stmt.setObject(2, message.messageId());
      stmt.setObject(3, message.senderUserId());
      stmt.setString(4, message.body());
      stmt.executeUpdate();
    } catch (SQLException e) {
      throw new RuntimeException("Failed to insert message", e);
    }
  }
}
