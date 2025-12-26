package testutils;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.websocket.CloseReason;
import io.micronaut.websocket.WebSocketClient;
import io.micronaut.websocket.WebSocketSession;
import io.micronaut.websocket.annotation.ClientWebSocket;
import io.micronaut.websocket.annotation.OnClose;
import io.micronaut.websocket.annotation.OnError;
import io.micronaut.websocket.annotation.OnMessage;
import io.micronaut.websocket.annotation.OnOpen;
import reactor.core.publisher.Flux;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ClientWebSocket
public abstract class TestMicronautWebSocketClient extends AbstractWebSocketClientTemplate {

  private final BlockingQueue<String> receivedMessages = new LinkedBlockingQueue<>();
  private final CompletableFuture<CloseReason> closeReasonFuture = new CompletableFuture<>();
  private volatile WebSocketSession session;

  @OnOpen
  public void onOpen(WebSocketSession session) {
    this.session = session;
  }

  @OnMessage
  public void onMessage(String message) {
    super.onMessage(message);
  }

  @OnClose
  public void onClose(CloseReason reason) {
    super.onClose(reason);
  }

  @OnError
  public void onError(Throwable t) {
    super.onError(t);
  }

  public abstract void send(String message);

  @Override
  public void close() {
    if (session != null && session.isOpen()) {
      session.close();
    }
  }

  public static TestMicronautWebSocketClient connect(WebSocketClient wsClient, URI uri, Map<String, String> headers) {
    MutableHttpRequest<?> req = HttpRequest.GET(uri);
    if (headers != null) headers.forEach(req::header);
    return Flux.from(wsClient.connect(TestMicronautWebSocketClient.class, req))
            .blockFirst(Duration.ofSeconds(5));
  }

  public static TestMicronautWebSocketClient connectAndAwaitAck(WebSocketClient wsClient, URI uri, Map<String, String> headers) throws Exception {
    TestMicronautWebSocketClient client = TestMicronautWebSocketClient.connect(wsClient, uri, headers);
    String msg = client.getReceivedMessages().poll(250, TimeUnit.MILLISECONDS);
    assertNotNull(msg, "Expected ack message after connect");
    assertTrue(msg.contains("\"type\":\"ack\""), "Expected ack, got: " + msg);
    return client;
  }
}
