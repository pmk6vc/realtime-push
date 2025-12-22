package messaging;

import static org.junit.jupiter.api.Assertions.*;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.websocket.CloseReason;
import io.micronaut.websocket.WebSocketClient;
import jakarta.inject.Inject;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import testing_util.TestWebSocketClient;

@MicronautTest
class MessagingServerIntegrationTest {

  @Inject EmbeddedServer server;
  @Inject WebSocketClient wsClient;

  private static final String USER_HEADER = "X-User-Id";

  private URI chatUri() {
    return server.getURI().resolve("/chat");
  }

  private TestWebSocketClient connect(URI uri, Map<String, String> headers) {
    MutableHttpRequest<?> req = HttpRequest.GET(uri);
    if (headers != null && !headers.isEmpty()) {
      headers.forEach(req::header);
    }
    return Flux.from(wsClient.connect(TestWebSocketClient.class, req))
        .blockFirst(Duration.ofSeconds(5));
  }

  private void awaitServerAck(TestWebSocketClient client) throws Exception {
    String msg = client.getReceivedMessages().poll(5, TimeUnit.SECONDS);
    assertNotNull(msg, "Expected ack message after connect");
    assertTrue(msg.contains("\"type\":\"ack\""), "Expected ack, got: " + msg);
  }

  @Test
  void onSessionOpen_closesWhenMissingUserIdHeader() throws Exception {
    TestWebSocketClient client = connect(chatUri(), null);
    CloseReason cr = client.getCloseReasonFuture().get(5, TimeUnit.SECONDS);
    assertEquals(CloseReason.POLICY_VIOLATION.getCode(), cr.getCode());
  }

  @Test
  void onSessionOpen_replacesPreexistingSession() throws Exception {
    TestWebSocketClient firstClient = connect(chatUri(), Map.of(USER_HEADER, "alice"));
    awaitServerAck(firstClient);
    TestWebSocketClient secondClient = connect(chatUri(), Map.of(USER_HEADER, "alice"));
    awaitServerAck(secondClient);
    try (firstClient;
        secondClient) {
      CloseReason cr = firstClient.getCloseReasonFuture().get(5, TimeUnit.SECONDS);
      assertNotNull(cr, "Expected first client to be closed");
      assertEquals(CloseReason.NORMAL.getCode(), cr.getCode());
      assertNull(
          secondClient.getCloseReasonFuture().getNow(null),
          "Expected second client to remain open");
    }
  }

  @Test
  void onMessage_broadcastsToOtherUsers() throws Exception {
    TestWebSocketClient aliceClient = connect(chatUri(), Map.of(USER_HEADER, "alice"));
    TestWebSocketClient bobClient = connect(chatUri(), Map.of(USER_HEADER, "bob"));
    awaitServerAck(aliceClient);
    awaitServerAck(bobClient);
    try (aliceClient;
        bobClient) {
      String messageFromAlice = "Hello, Bob!";
      aliceClient.send(messageFromAlice);

      String aliceReceivedMessage = aliceClient.getReceivedMessages().poll(5, TimeUnit.SECONDS);
      String bobReceivedMessage = bobClient.getReceivedMessages().poll(5, TimeUnit.SECONDS);
      assertNull(aliceReceivedMessage);
      assertNotNull(bobReceivedMessage);
      assertTrue(bobReceivedMessage.contains(messageFromAlice));
    }
  }

  @Test
  void onMessage_doesNotBroadcastToDisconnectedUsers() throws Exception {
    TestWebSocketClient aliceClient = connect(chatUri(), Map.of(USER_HEADER, "alice"));
    TestWebSocketClient bobClient = connect(chatUri(), Map.of(USER_HEADER, "bob"));
    awaitServerAck(aliceClient);
    awaitServerAck(bobClient);
    try (aliceClient;
        bobClient) {
      bobClient.close();
      assertNotNull(bobClient.getCloseReasonFuture().get(5, TimeUnit.SECONDS));

      String messageFromAlice = "Is anyone there?";
      aliceClient.send(messageFromAlice);
      assertNull(aliceClient.getReceivedMessages().poll(5, TimeUnit.SECONDS));
    }
  }

  @Test
  void onMessage_messageOrderPreserved() throws Exception {
    TestWebSocketClient aliceClient = connect(chatUri(), Map.of(USER_HEADER, "alice"));
    TestWebSocketClient bobClient = connect(chatUri(), Map.of(USER_HEADER, "bob"));
    awaitServerAck(aliceClient);
    awaitServerAck(bobClient);
    try (aliceClient;
        bobClient) {
      for (int i = 1; i <= 5; i++) {
        String message = "Message " + i;
        aliceClient.send(message);
      }

      for (int i = 1; i <= 5; i++) {
        String received = bobClient.getReceivedMessages().poll(5, TimeUnit.SECONDS);
        assertNotNull(received, "Expected message " + i + " but got none");
        assertTrue(
            received.contains("Message " + i), "Expected message " + i + " but got: " + received);
      }
    }
  }
}
