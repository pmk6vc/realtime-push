package messaging;

import io.micronaut.websocket.WebSocketSession;
import io.micronaut.websocket.annotation.*;
import java.util.Set;

// TODO: Parse JWT for user ID instead of including in path
@ServerWebSocket("/ws/chat/{user}")
public class MessagingServer {

  private static final String ATTR_USER_ID = "userId";
  private final ConnectionRegistry userConnRegistry;

  public MessagingServer(ConnectionRegistry userConnRegistry) {
    this.userConnRegistry = userConnRegistry;
  }

  @OnOpen
  public void onSessionOpen(String user, WebSocketSession session) {
    // TODO: Use data from JWT instead of injecting path param in method
    session.put(ATTR_USER_ID, user);
    userConnRegistry.registerUserSession(user, session);
    session.sendAsync("{\"type\":\"system\",\"text\":\"connected\",\"userId\":\"" + user + "\"}");
  }

  @OnClose
  public void onSessionClose(WebSocketSession session) {
    String userId = session.get(ATTR_USER_ID, String.class, null);
    if (userId != null) {
      userConnRegistry.removeUserSession(userId, session);
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
    String payload =
        "{\"type\":\"message\",\"from\":\""
            + userId
            + "\",\"text\":\""
            + escapeJson(message)
            + "\"}";
    userConnRegistry.broadcastPayloadWithExclusions(payload, Set.of(userId));
  }

  @OnError
  public void onSessionError(WebSocketSession session, Throwable t) {
    String userId = session.get(ATTR_USER_ID, String.class, null);
    if (userId != null) {
      // TODO: Log error
      userConnRegistry.removeUserSession(userId, session);
    }
  }

  public void onKafkaFanoutMessage(String fromUserId, String channelId, String payload) {
    // TODO: Add Kafka subscription
    // TODO: Fetch relevant channel members from Redis, exclude sender, and broadcast payload to
    // targets
  }

  private static String escapeJson(String s) {
    return s.replace("\\", "\\\\").replace("\"", "\\\"");
  }
}
