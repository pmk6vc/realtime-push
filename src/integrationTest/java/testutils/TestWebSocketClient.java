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
public abstract class TestWebSocketClient implements AutoCloseable {

  private final BlockingQueue<String> receivedMessages = new LinkedBlockingQueue<>();
  private final CompletableFuture<CloseReason> closeReasonFuture = new CompletableFuture<>();
  private volatile WebSocketSession session;

  @OnOpen
  void onOpen(WebSocketSession session) {
    this.session = session;
  }

  @OnMessage
  void onMessage(String message) {
    receivedMessages.add(message);
  }

  @OnClose
  void onClose(CloseReason reason) {
    closeReasonFuture.complete(reason);
  }

  @OnError
  public void onError(Throwable t) {
    closeReasonFuture.completeExceptionally(t);
  }

  public BlockingQueue<String> getReceivedMessages() {
    return receivedMessages;
  }

  public CompletableFuture<CloseReason> getCloseReasonFuture() {
    return closeReasonFuture;
  }

  public abstract void send(String message);

  public void close() {
    if (session != null && session.isOpen()) {
      session.close();
    }
  }

  public static TestWebSocketClient connect(WebSocketClient wsClient, URI uri, Map<String, String> headers) {
    MutableHttpRequest<?> req = HttpRequest.GET(uri);
    if (headers != null) headers.forEach(req::header);
    return Flux.from(wsClient.connect(TestWebSocketClient.class, req))
            .blockFirst(Duration.ofSeconds(5));
  }

  public static TestWebSocketClient connectAndAwaitAck(WebSocketClient wsClient, URI uri, Map<String, String> headers) throws Exception {
    TestWebSocketClient client = TestWebSocketClient.connect(wsClient, uri, headers);
    String msg = client.getReceivedMessages().poll(250, TimeUnit.MILLISECONDS);
    assertNotNull(msg, "Expected ack message after connect");
    assertTrue(msg.contains("\"type\":\"ack\""), "Expected ack, got: " + msg);
    return client;
  }
}
