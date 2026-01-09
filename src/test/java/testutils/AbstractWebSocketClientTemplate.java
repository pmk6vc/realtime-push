package testutils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micronaut.websocket.CloseReason;
import java.util.concurrent.*;

/**
 * Abstract base class for WebSocket clients used in tests. Consolidating shared logic here since
 * E2E and Micronaut test clients have similar needs but cannot use the same class. Specifically
 * Micronaut client must instantiate a Micronaut application context and rely on DI. E2E tests
 * deliberately avoid Micronaut application context setup and DI to avoid potential collisions
 * across shared infra.
 *
 * <p>Method names are deliberately explicit here to avoid shadowing with overridden methods in
 * WebSocket classes.
 */
public class AbstractWebSocketClientTemplate implements AutoCloseable {

  private final BlockingQueue<String> receivedMessages = new LinkedBlockingQueue<>();
  private final CompletableFuture<CloseReason> closeReasonFuture = new CompletableFuture<>();
  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Override
  public void close() {}

  public void onWebSocketMessage(String msg) {
    receivedMessages.add(msg);
  }

  public void onWebSocketClose(CloseReason r) {
    closeReasonFuture.complete(r);
  }

  public void onWebSocketError(Throwable t) {
    closeReasonFuture.completeExceptionally(t);
  }

  public BlockingQueue<String> getReceivedMessages() {
    return receivedMessages;
  }

  public CompletableFuture<CloseReason> getCloseReasonFuture() {
    return closeReasonFuture;
  }

  public JsonNode awaitAck() throws Exception {
    String msg = getReceivedMessages().poll(250, TimeUnit.MILLISECONDS);
    assertNotNull(msg, "Expected ack message after connect");
    JsonNode json = MAPPER.readTree(msg);
    assertEquals("ack", json.get("type").asText(), "Expected ack, got: " + msg);
    assertNotNull(json.get("userId"), "Ack missing userId: " + msg);
    return json;
  }
}
