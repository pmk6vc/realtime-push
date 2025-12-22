package messaging;

import io.micronaut.http.HttpRequest;
import io.micronaut.websocket.CloseReason;
import io.micronaut.websocket.WebSocketSession;
import io.micronaut.websocket.annotation.*;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import util.HeaderUserIdExtractor;

@ServerWebSocket("/chat")
public class MessagingServer {

  private static final String ATTR_USER_ID = "userId";
  private final ConnectionRegistry userConnRegistry;
  private final HeaderUserIdExtractor headerUserIdExtractor;
  private static final Logger LOG = LoggerFactory.getLogger(MessagingServer.class);

  public MessagingServer(
      ConnectionRegistry userConnRegistry, HeaderUserIdExtractor headerUserIdExtractor) {
    this.userConnRegistry = userConnRegistry;
    this.headerUserIdExtractor = headerUserIdExtractor;
  }

  @OnOpen
  public void onSessionOpen(WebSocketSession session, HttpRequest<?> request) {
    Optional<String> userIdOpt = headerUserIdExtractor.extract(request);
    if (userIdOpt.isEmpty()) {
      LOG.warn("Closing WebSocket session due to missing user ID in headers: {}", session.getId());
      session.close(
          new CloseReason(
              CloseReason.POLICY_VIOLATION.getCode(),
              "Could not extract valid user ID from request headers"));
      return;
    }
    String userId = userIdOpt.get();
    session.put(ATTR_USER_ID, userId);
    userConnRegistry.registerUserSession(userId, session);
    LOG.info("WebSocket opened for userId {}: {}", userId, session.getId());
  }

  @OnClose
  public void onSessionClose(WebSocketSession session) {
    String userId = session.get(ATTR_USER_ID, String.class, null);
    if (userId != null) {
      userConnRegistry.removeUserSession(userId, session);
      LOG.info("WebSocket closed for userId {}: {}", userId, session.getId());
    } else {
      LOG.info("WebSocket closed for unknown user: {}", session.getId());
    }
  }

  @OnMessage
  public void onSessionMessage(String message, WebSocketSession session) {
    // TODO: Write message to DB - separate outbox will handle publishing to Kafka for fanout
    // Messages must be written to DB first - otherwise new clients may join in between fanout and
    // DB write and miss messages
    // For now, just echo back to other users registered on this server
    // Avoid doing any broadcasting in this method in the steady state - delegate to Kafka fanout
    // instead
    String userId = session.get(ATTR_USER_ID, String.class, null);
    userConnRegistry.broadcastPayloadWithExclusions(buildPayload(userId, message), Set.of(userId));
  }

  @OnError
  public void onSessionError(WebSocketSession session, Throwable t) {
    String userId = session.get(ATTR_USER_ID, String.class, null);
    if (userId != null) {
      LOG.error(t.getMessage(), t);
      userConnRegistry.removeUserSession(userId, session);
    }
  }

  public void onFanoutMessage(String fromUserId, String channelId, String payload) {
    // TODO: Add Kafka subscription
    // TODO: Fetch relevant channel members from Redis, exclude sender, and broadcast payload to
    // targets
  }

  private static String buildPayload(String userId, String message) {
    return "{\"type\":\"message\",\"from\":\""
            + userId
            + "\",\"text\":\""
            + message.replace("\\", "\\\\").replace("\"", "\\\"")
            + "\"}";
  }
}
