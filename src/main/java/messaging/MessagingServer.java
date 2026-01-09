package messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micronaut.http.HttpRequest;
import io.micronaut.websocket.CloseReason;
import io.micronaut.websocket.WebSocketSession;
import io.micronaut.websocket.annotation.*;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import messaging.persistence.Message;
import messaging.persistence.MessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import util.HeaderUserIdExtractor;

@ServerWebSocket("/chat")
public class MessagingServer {

  private static final String ATTR_USER_ID = "userId";
  private final ConnectionRegistry userConnRegistry;
  private final HeaderUserIdExtractor headerUserIdExtractor;
  private final MessageRepository messageRepository;
  private final ObjectMapper objectMapper;
  private static final Logger LOG = LoggerFactory.getLogger(MessagingServer.class);

  public MessagingServer(
      ConnectionRegistry userConnRegistry,
      HeaderUserIdExtractor headerUserIdExtractor,
      MessageRepository messageRepository,
      ObjectMapper objectMapper) {
    this.userConnRegistry = userConnRegistry;
    this.headerUserIdExtractor = headerUserIdExtractor;
    this.messageRepository = messageRepository;
    this.objectMapper = objectMapper;
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
    String ackPayload =
        "{\"type\":\"ack\",\"userId\":\""
            + userId
            + "\",\"sessionId\":\""
            + session.getId()
            + "\"}";
    session.sendAsync(ackPayload);
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
    String userId = session.get(ATTR_USER_ID, String.class, null);
    if (userId == null) {
      LOG.warn("Received message from session without userId: {}", session.getId());
      sendErrorResponse(session, "Missing user ID. Please reconnect.");
      return;
    }

    // Validate userId is a valid UUID
    try {
      UUID.fromString(userId);
    } catch (IllegalArgumentException e) {
      LOG.error("Invalid userId format: {}", userId, e);
      sendErrorResponse(session, "Invalid user ID format");
      return;
    }

    try {
      // Parse incoming message JSON
      IncomingMessage incomingMessage = objectMapper.readValue(message, IncomingMessage.class);

      // Validate required fields
      if (incomingMessage.channelId() == null) {
        sendErrorResponse(
            session,
            "Invalid message format. Expected JSON: {\"channelId\":\"<uuid>\",\"text\":\"<message>\"}");
        return;
      }
      if (incomingMessage.text() == null || incomingMessage.text().isBlank()) {
        sendErrorResponse(session, "Message text cannot be empty");
        return;
      }

      // Create and persist message to database
      Message messageToSave =
          Message.create(
              incomingMessage.channelId(), UUID.fromString(userId), incomingMessage.text());
      Message savedMessage = messageRepository.save(messageToSave);

      LOG.info(
          "Persisted message {} to channel {} from user {}",
          savedMessage.messageId(),
          savedMessage.channelId(),
          userId);

      // TODO: Write to outbox table for Kafka fanout (Phase 1, item 3)
      // For now, broadcast locally to other users on this server instance
      String broadcastPayload = buildBroadcastPayload(savedMessage);
      userConnRegistry.broadcastPayloadWithExclusions(broadcastPayload, Set.of(userId));

    } catch (JsonProcessingException e) {
      LOG.error("Failed to parse message JSON from user {}: {}", userId, message, e);
      sendErrorResponse(
          session,
          "Invalid message format. Expected JSON: {\"channelId\":\"<uuid>\",\"text\":\"<message>\"}");
    } catch (Exception e) {
      LOG.error("Failed to persist message from user {}: {}", userId, message, e);
      sendErrorResponse(session, "Failed to send message. Please try again.");
    }
  }

  private void sendErrorResponse(WebSocketSession session, String errorMessage) {
    String errorPayload = "{\"type\":\"error\",\"message\":\"" + escapeJson(errorMessage) + "\"}";
    session.sendAsync(errorPayload);
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

  private String buildBroadcastPayload(Message message) {
    return "{\"type\":\"message\",\"messageId\":\""
        + message.messageId()
        + "\",\"channelId\":\""
        + message.channelId()
        + "\",\"from\":\""
        + message.senderUserId()
        + "\",\"sentAt\":\""
        + message.sentAt()
        + "\",\"text\":\""
        + escapeJson(message.body())
        + "\"}";
  }

  private String escapeJson(String text) {
    return text.replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r");
  }
}
