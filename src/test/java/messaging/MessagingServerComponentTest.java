package messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static testutils.MicronautTestWebSocketClient.connectAndAwaitAck;

import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.websocket.CloseReason;
import io.micronaut.websocket.WebSocketClient;
import jakarta.inject.Inject;
import java.net.URI;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import testutils.MicronautTestWebSocketClient;

@MicronautTest(environments = "test")
class MessagingServerComponentTest {

  private static final String TEST_CHANNEL_ID = "00000000-0000-0000-0000-000000000001";
  private static final String USER_HEADER = "X-User-Id";
  private static final String ALICE_ID = "11111111-1111-1111-1111-111111111111";
  private static final String BOB_ID = "22222222-2222-2222-2222-222222222222";

  @Inject EmbeddedServer server;
  @Inject WebSocketClient wsClient;

  private URI chatUri() {
    return server.getURI().resolve("/chat");
  }

  private String buildMessageJson(String text) {
    return String.format("{\"channelId\":\"%s\",\"text\":\"%s\"}", TEST_CHANNEL_ID, text);
  }

  @Test
  void onSessionOpen_closesWhenMissingUserIdHeader() throws Exception {
    MicronautTestWebSocketClient client =
        MicronautTestWebSocketClient.connect(wsClient, chatUri(), null);
    CloseReason cr = client.getCloseReasonFuture().get(5, TimeUnit.SECONDS);
    assertEquals(CloseReason.POLICY_VIOLATION.getCode(), cr.getCode());
  }

  @Test
  void onSessionOpen_replacesPreexistingSession() throws Exception {
    MicronautTestWebSocketClient firstClient =
        connectAndAwaitAck(wsClient, chatUri(), Map.of(USER_HEADER, ALICE_ID));
    MicronautTestWebSocketClient secondClient =
        connectAndAwaitAck(wsClient, chatUri(), Map.of(USER_HEADER, ALICE_ID));
    try (firstClient;
        secondClient) {
      CloseReason cr = firstClient.getCloseReasonFuture().get(250, TimeUnit.MILLISECONDS);
      assertNotNull(cr, "Expected first client to be closed");
      assertEquals(CloseReason.NORMAL.getCode(), cr.getCode());
      assertThrows(
          TimeoutException.class,
          () -> secondClient.getCloseReasonFuture().get(250, TimeUnit.MILLISECONDS),
          "Second client should remain open");
    }
  }

  @Test
  void onMessage_broadcastsToOtherUsers() throws Exception {
    MicronautTestWebSocketClient aliceClient =
        connectAndAwaitAck(wsClient, chatUri(), Map.of(USER_HEADER, ALICE_ID));
    MicronautTestWebSocketClient bobClient =
        connectAndAwaitAck(wsClient, chatUri(), Map.of(USER_HEADER, BOB_ID));
    try (aliceClient;
        bobClient) {
      String messageText = "Hello, Bob!";
      aliceClient.send(buildMessageJson(messageText));

      String aliceReceivedMessage =
          aliceClient.getReceivedMessages().poll(250, TimeUnit.MILLISECONDS);
      String bobReceivedMessage = bobClient.getReceivedMessages().poll(250, TimeUnit.MILLISECONDS);
      assertNull(aliceReceivedMessage);
      assertNotNull(bobReceivedMessage);
      assertTrue(bobReceivedMessage.contains(messageText));
    }
  }

  @Test
  void onMessage_doesNotBroadcastToDisconnectedUsers() throws Exception {
    MicronautTestWebSocketClient aliceClient =
        connectAndAwaitAck(wsClient, chatUri(), Map.of(USER_HEADER, ALICE_ID));
    MicronautTestWebSocketClient bobClient =
        connectAndAwaitAck(wsClient, chatUri(), Map.of(USER_HEADER, BOB_ID));
    try (aliceClient;
        bobClient) {
      bobClient.close();
      assertNotNull(bobClient.getCloseReasonFuture().get(250, TimeUnit.MILLISECONDS));

      String messageText = "Is anyone there?";
      aliceClient.send(buildMessageJson(messageText));
      assertNull(aliceClient.getReceivedMessages().poll(250, TimeUnit.MILLISECONDS));
    }
  }

  @Test
  void onMessage_multipleMessagesDeliveredWithoutDuplicates() throws Exception {
    MicronautTestWebSocketClient aliceClient =
        connectAndAwaitAck(wsClient, chatUri(), Map.of(USER_HEADER, ALICE_ID));
    MicronautTestWebSocketClient bobClient =
        connectAndAwaitAck(wsClient, chatUri(), Map.of(USER_HEADER, BOB_ID));
    Set<String> receivedMessages = new HashSet<>();
    try (aliceClient;
        bobClient) {
      for (int i = 1; i <= 5; i++) {
        String messageText = "Message " + i;
        aliceClient.send(buildMessageJson(messageText));
      }
      for (int i = 1; i <= 5; i++) {
        String received = bobClient.getReceivedMessages().poll(250, TimeUnit.MILLISECONDS);
        assertNotNull(received, "Expected message " + i + " but got none");
        assertFalse(receivedMessages.contains(received), "Duplicate message received: " + received);
        receivedMessages.add(received);
      }
    }
  }

  @Test
  void onMessage_invalidJsonReturnsError() throws Exception {
    MicronautTestWebSocketClient aliceClient =
        connectAndAwaitAck(wsClient, chatUri(), Map.of(USER_HEADER, ALICE_ID));
    try (aliceClient) {
      // Send invalid message (not JSON)
      aliceClient.send("This is not JSON");

      // Should receive error response
      String errorResponse = aliceClient.getReceivedMessages().poll(250, TimeUnit.MILLISECONDS);
      assertNotNull(errorResponse, "Should receive error response");
      assertTrue(errorResponse.contains("\"type\":\"error\""));
      assertTrue(errorResponse.contains("Invalid message format"));
    }
  }

  @Test
  void onMessage_missingChannelIdReturnsError() throws Exception {
    MicronautTestWebSocketClient aliceClient =
        connectAndAwaitAck(wsClient, chatUri(), Map.of(USER_HEADER, ALICE_ID));
    try (aliceClient) {
      // Send message with missing channelId
      aliceClient.send("{\"text\":\"Hello\"}");

      // Should receive error response (may take longer due to JSON parsing)
      String errorResponse = aliceClient.getReceivedMessages().poll(1, TimeUnit.SECONDS);
      assertNotNull(errorResponse, "Should receive error response for missing channelId");
      assertTrue(errorResponse.contains("\"type\":\"error\""));
      assertTrue(errorResponse.contains("Invalid message format"));
    }
  }
}
