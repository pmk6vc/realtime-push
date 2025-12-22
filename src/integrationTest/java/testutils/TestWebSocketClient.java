package testutils;

import io.micronaut.websocket.CloseReason;
import io.micronaut.websocket.WebSocketSession;
import io.micronaut.websocket.annotation.ClientWebSocket;
import io.micronaut.websocket.annotation.OnClose;
import io.micronaut.websocket.annotation.OnError;
import io.micronaut.websocket.annotation.OnMessage;
import io.micronaut.websocket.annotation.OnOpen;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;

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
}
