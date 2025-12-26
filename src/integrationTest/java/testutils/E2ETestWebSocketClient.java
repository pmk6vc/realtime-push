package testutils;

import static org.junit.jupiter.api.Assertions.*;

import io.micronaut.websocket.CloseReason;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import okhttp3.*;
import org.jetbrains.annotations.NotNull;

public final class E2ETestWebSocketClient extends AbstractWebSocketClientTemplate {
  private final WebSocket socket;
  private final CountDownLatch opened = new CountDownLatch(1);

  private E2ETestWebSocketClient(OkHttpClient client, Request req) {
    this.socket =
        client.newWebSocket(
            req,
            new WebSocketListener() {
              @Override
              public void onOpen(@NotNull WebSocket webSocket, @NotNull Response response) {
                opened.countDown();
              }

              @Override
              public void onMessage(@NotNull WebSocket webSocket, @NotNull String text) {
                onWebSocketMessage(text);
              }

              @Override
              public void onFailure(
                  @NotNull WebSocket webSocket, @NotNull Throwable t, Response response) {
                onWebSocketError(t);
              }

              @Override
              public void onClosed(@NotNull WebSocket webSocket, int code, @NotNull String reason) {
                onWebSocketClose(CloseReason.NORMAL);
              }
            });
  }

  private void awaitOpen() throws InterruptedException {
    if (!opened.await(Duration.ofSeconds(5).toSeconds(), TimeUnit.SECONDS)) {
      throw new IllegalStateException("Timed out waiting for websocket open");
    }
  }

  public void sendMessage(String msg) {
    socket.send(msg);
  }

  @Override
  public void close() {
    socket.close(1000, "bye");
    socket.cancel();
  }

  public static E2ETestWebSocketClient connect(URI uri, Map<String, String> headers)
      throws Exception {
    OkHttpClient client = new OkHttpClient.Builder().build();
    Request.Builder rb = new Request.Builder().url(uri.toString());
    if (headers != null) headers.forEach(rb::addHeader);
    E2ETestWebSocketClient ws = new E2ETestWebSocketClient(client, rb.build());
    try {
      ws.awaitOpen();
      return ws;
    } catch (Exception e) {
      ws.close();
      throw e;
    }
  }

  public static E2ETestWebSocketClient connectAndAwaitAck(URI uri, Map<String, String> headers)
      throws Exception {
    E2ETestWebSocketClient client = E2ETestWebSocketClient.connect(uri, headers);
    client.awaitAck();
    return client;
  }
}
