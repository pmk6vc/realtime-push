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
  void onSessionOpen_closesWhenNoUserIdPresent() throws Exception {
    TestWebSocketClient client = connect(chatUri(), null);

    CloseReason cr = client.getCloseReasonFuture().get(5, TimeUnit.SECONDS);
    assertEquals(CloseReason.POLICY_VIOLATION.getCode(), cr.getCode());
  }

  @Test
  void onMessage_broadcastsToOtherUsers() throws Exception {
    TestWebSocketClient aliceClient = connect(chatUri(), Map.of(USER_HEADER, "alice"));
    TestWebSocketClient bobClient = connect(chatUri(), Map.of(USER_HEADER, "bob"));
    awaitServerAck(aliceClient);
    awaitServerAck(bobClient);

    String messageFromAlice = "Hello, Bob!";
    aliceClient.send(messageFromAlice);

    String aliceReceivedMessage = aliceClient.getReceivedMessages().poll(5, TimeUnit.SECONDS);
    String bobReceivedMessage = bobClient.getReceivedMessages().poll(5, TimeUnit.SECONDS);
    assertNull(aliceReceivedMessage);
    assertNotNull(bobReceivedMessage);
    assertTrue(bobReceivedMessage.contains(messageFromAlice));
  }
}
