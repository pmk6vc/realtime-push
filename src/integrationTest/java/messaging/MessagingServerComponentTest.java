package messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static testutils.TestWebSocketClient.connectAndAwaitAck;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.websocket.CloseReason;
import io.micronaut.websocket.WebSocketClient;
import jakarta.inject.Inject;
import java.net.URI;
import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import testutils.TestWebSocketClient;

@MicronautTest
class MessagingServerComponentTest {

  @Inject EmbeddedServer server;
  @Inject WebSocketClient wsClient;

  private static final String USER_HEADER = "X-User-Id";

  private URI chatUri() {
    return server.getURI().resolve("/chat");
  }

  @Test
  void onSessionOpen_closesWhenMissingUserIdHeader() throws Exception {
    TestWebSocketClient client = TestWebSocketClient.connect(wsClient, chatUri(), null);
    CloseReason cr = client.getCloseReasonFuture().get(5, TimeUnit.SECONDS);
    assertEquals(CloseReason.POLICY_VIOLATION.getCode(), cr.getCode());
  }

  @Test
  void onSessionOpen_replacesPreexistingSession() throws Exception {
    TestWebSocketClient firstClient = connectAndAwaitAck(wsClient, chatUri(), Map.of(USER_HEADER, "alice"));
    TestWebSocketClient secondClient = connectAndAwaitAck(wsClient, chatUri(), Map.of(USER_HEADER, "alice"));
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
    TestWebSocketClient aliceClient = connectAndAwaitAck(wsClient, chatUri(), Map.of(USER_HEADER, "alice"));
    TestWebSocketClient bobClient = connectAndAwaitAck(wsClient, chatUri(), Map.of(USER_HEADER, "bob"));
    try (aliceClient;
        bobClient) {
      String messageFromAlice = "Hello, Bob!";
      aliceClient.send(messageFromAlice);

      String aliceReceivedMessage =
          aliceClient.getReceivedMessages().poll(250, TimeUnit.MILLISECONDS);
      String bobReceivedMessage = bobClient.getReceivedMessages().poll(250, TimeUnit.MILLISECONDS);
      assertNull(aliceReceivedMessage);
      assertNotNull(bobReceivedMessage);
      assertTrue(bobReceivedMessage.contains(messageFromAlice));
    }
  }

  @Test
  void onMessage_doesNotBroadcastToDisconnectedUsers() throws Exception {
    TestWebSocketClient aliceClient = connectAndAwaitAck(wsClient, chatUri(), Map.of(USER_HEADER, "alice"));
    TestWebSocketClient bobClient = connectAndAwaitAck(wsClient, chatUri(), Map.of(USER_HEADER, "bob"));
    try (aliceClient;
        bobClient) {
      bobClient.close();
      assertNotNull(bobClient.getCloseReasonFuture().get(250, TimeUnit.MILLISECONDS));

      String messageFromAlice = "Is anyone there?";
      aliceClient.send(messageFromAlice);
      assertNull(aliceClient.getReceivedMessages().poll(250, TimeUnit.MILLISECONDS));
    }
  }

  @Test
  void onMessage_multipleMessagesDeliveredWithoutDuplicates() throws Exception {
    TestWebSocketClient aliceClient = connectAndAwaitAck(wsClient, chatUri(), Map.of(USER_HEADER, "alice"));
    TestWebSocketClient bobClient = connectAndAwaitAck(wsClient, chatUri(), Map.of(USER_HEADER, "bob"));
    Set<String> receivedMessages = new HashSet<>();
    try (aliceClient;
        bobClient) {
      for (int i = 1; i <= 5; i++) {
        String message = "Message " + i;
        aliceClient.send(message);
      }
      for (int i = 1; i <= 5; i++) {
        String received = bobClient.getReceivedMessages().poll(250, TimeUnit.MILLISECONDS);
        assertNotNull(received, "Expected message " + i + " but got none");
        assertFalse(receivedMessages.contains(received), "Duplicate message received: " + received);
        receivedMessages.add(received);
      }
    }
  }
}
