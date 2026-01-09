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
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import reactor.core.publisher.Flux;

@ClientWebSocket
public abstract class MicronautTestWebSocketClient extends AbstractWebSocketClientTemplate {

  private volatile WebSocketSession session;

  @OnOpen
  public void onOpen(WebSocketSession session) {
    this.session = session;
  }

  @OnMessage
  public void onMessage(String message) {
    super.onWebSocketMessage(message);
  }

  @OnClose
  public void onClose(CloseReason reason) {
    super.onWebSocketClose(reason);
  }

  @OnError
  public void onError(Throwable t) {
    super.onWebSocketError(t);
  }

  public abstract void send(String message);

  @Override
  public void close() {
    if (session != null && session.isOpen()) {
      session.close();
    }
  }

  public static MicronautTestWebSocketClient connect(
      WebSocketClient wsClient, URI uri, Map<String, String> headers) {
    MutableHttpRequest<?> req = HttpRequest.GET(uri);
    if (headers != null) headers.forEach(req::header);
    return Flux.from(wsClient.connect(MicronautTestWebSocketClient.class, req))
        .blockFirst(Duration.ofSeconds(5));
  }

  public static MicronautTestWebSocketClient connectAndAwaitAck(
      WebSocketClient wsClient, URI uri, Map<String, String> headers) throws Exception {
    MicronautTestWebSocketClient client =
        MicronautTestWebSocketClient.connect(wsClient, uri, headers);
    client.awaitAck();
    return client;
  }
}
