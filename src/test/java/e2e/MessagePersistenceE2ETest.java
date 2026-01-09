package e2e;

import static org.junit.jupiter.api.Assertions.*;
import static testutils.IntegrationInfraExtension.TEST_CHANNEL_ID;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import testutils.E2ETestWebSocketClient;
import testutils.IntegrationInfraExtension;
import testutils.IntegrationInfraExtension.Infra;

@ExtendWith(IntegrationInfraExtension.class)
@Tag("e2e")
class MessagePersistenceE2ETest {

  private URI envoyChatWsUri(Infra infra) {
    String http = infra.envoyBaseUrl();
    String ws =
        http.startsWith("https://")
            ? "wss://" + http.substring("https://".length())
            : http.startsWith("http://") ? "ws://" + http.substring("http://".length()) : http;
    return URI.create(ws + "/chat");
  }

  private Connection getCitusConnection(Infra infra) throws Exception {
    String host = infra.citusMasterContainer().getHost();
    Integer port = infra.citusMasterContainer().getMappedPort(5432);
    String jdbcUrl = String.format("jdbc:postgresql://%s:%d/citus", host, port);
    return DriverManager.getConnection(jdbcUrl, "citus", "citus");
  }

  @AfterEach
  void cleanupMessages(Infra infra) throws Exception {
    // Delete all messages from test channel between tests
    infra.testDataManager().deleteMessagesFromChannel(TEST_CHANNEL_ID);
  }

  @Test
  void sendMessage_persistsToDatabase(Infra infra) throws Exception {
    String aliceToken = infra.passwordGrant("alice", "alice!");
    String bobToken = infra.passwordGrant("bob", "bob!");
    String aliceUserId = infra.userSub("alice");
    URI wsUri = envoyChatWsUri(infra);

    try (E2ETestWebSocketClient aliceClient =
            E2ETestWebSocketClient.connect(wsUri, Map.of("Authorization", "Bearer " + aliceToken));
        E2ETestWebSocketClient bobClient =
            E2ETestWebSocketClient.connect(wsUri, Map.of("Authorization", "Bearer " + bobToken))) {

      // Wait for acks
      aliceClient.awaitAck();
      bobClient.awaitAck();

      // Send message from Alice
      String messageText = "Hello from Alice!";
      String messageJson =
          String.format("{\"channelId\":\"%s\",\"text\":\"%s\"}", TEST_CHANNEL_ID, messageText);
      aliceClient.sendMessage(messageJson);

      // Wait for Bob to receive the message (this means DB write completed)
      String bobReceivedMessage = bobClient.getReceivedMessages().poll(2, TimeUnit.SECONDS);
      assertNotNull(bobReceivedMessage, "Bob should receive Alice's message");

      // Verify message was written to database
      try (Connection conn = getCitusConnection(infra);
          PreparedStatement stmt =
              conn.prepareStatement(
                  "SELECT message_id, sender_user_id, body, sent_at "
                      + "FROM messages WHERE channel_id = ?")) {
        stmt.setObject(1, UUID.fromString(TEST_CHANNEL_ID));
        try (ResultSet rs = stmt.executeQuery()) {
          assertTrue(rs.next(), "Expected message to be persisted in database");

          UUID messageId = (UUID) rs.getObject("message_id");
          UUID senderUserId = (UUID) rs.getObject("sender_user_id");
          String body = rs.getString("body");

          assertNotNull(messageId, "Message ID should not be null");
          assertEquals(
              UUID.fromString(aliceUserId), senderUserId, "Sender user ID should match Alice");
          assertEquals(messageText, body, "Message body should match");

          assertFalse(rs.next(), "Expected only one message");
        }
      }
    }
  }

  @Test
  void sendInvalidMessage_returnsError(Infra infra) throws Exception {
    String aliceToken = infra.passwordGrant("alice", "alice!");
    URI wsUri = envoyChatWsUri(infra);

    try (E2ETestWebSocketClient aliceClient =
        E2ETestWebSocketClient.connect(wsUri, Map.of("Authorization", "Bearer " + aliceToken))) {

      // Wait for ack
      aliceClient.awaitAck();

      // Send invalid message (not JSON)
      aliceClient.sendMessage("This is not JSON");

      // Should receive error response
      String errorResponse = aliceClient.getReceivedMessages().poll(2, TimeUnit.SECONDS);
      assertNotNull(errorResponse, "Should receive error response");

      JsonNode errorNode = infra.mapper().readTree(errorResponse);
      assertEquals("error", errorNode.get("type").asText());
      assertTrue(
          errorNode.get("message").asText().contains("Invalid message format"),
          "Error message should mention invalid format");
    }
  }

  @Test
  void sendMessageWithMissingChannelId_returnsError(Infra infra) throws Exception {
    String aliceToken = infra.passwordGrant("alice", "alice!");
    URI wsUri = envoyChatWsUri(infra);

    try (E2ETestWebSocketClient aliceClient =
        E2ETestWebSocketClient.connect(wsUri, Map.of("Authorization", "Bearer " + aliceToken))) {

      // Wait for ack
      aliceClient.awaitAck();

      // Send message with missing channelId
      aliceClient.sendMessage("{\"text\":\"Hello\"}");

      // Should receive error response
      String errorResponse = aliceClient.getReceivedMessages().poll(2, TimeUnit.SECONDS);
      assertNotNull(errorResponse, "Should receive error response for missing channelId");

      JsonNode errorNode = infra.mapper().readTree(errorResponse);
      assertEquals("error", errorNode.get("type").asText());
    }
  }
}
