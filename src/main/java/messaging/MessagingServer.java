package messaging;

import channel.persistence.ChannelMember;
import channel.persistence.ChannelMemberRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micronaut.http.HttpRequest;
import io.micronaut.websocket.CloseReason;
import io.micronaut.websocket.WebSocketSession;
import io.micronaut.websocket.annotation.*;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import messaging.message.AckMessage;
import messaging.message.BroadcastMessage;
import messaging.message.ErrorMessage;
import messaging.persistence.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import util.HeaderUserIdExtractor;
import util.exception.MessagePersistenceException;

@ServerWebSocket("/chat")
public class MessagingServer {

  private static final String ATTR_USER_ID = "userId";
  private final ConnectionRegistry userConnRegistry;
  private final HeaderUserIdExtractor headerUserIdExtractor;
  private final MessageService messageService;
  private final ChannelMemberRepository channelMemberRepository;
  private final ObjectMapper objectMapper;
  private static final Logger LOG = LoggerFactory.getLogger(MessagingServer.class);

  public MessagingServer(
      ConnectionRegistry userConnRegistry,
      HeaderUserIdExtractor headerUserIdExtractor,
      MessageService messageService,
      ChannelMemberRepository channelMemberRepository,
      ObjectMapper objectMapper) {
    this.userConnRegistry = userConnRegistry;
    this.headerUserIdExtractor = headerUserIdExtractor;
    this.messageService = messageService;
    this.channelMemberRepository = channelMemberRepository;
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
    try {
      String ackPayload =
          objectMapper.writeValueAsString(AckMessage.create(userId, session.getId()));
      session.sendAsync(ackPayload);
    } catch (JsonProcessingException e) {
      LOG.error("Failed to serialize ack message for userId {}", userId, e);
    }
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

      UUID channelId = incomingMessage.channelId();
      UUID senderUserId = UUID.fromString(userId);

      // Check if sender is a member of the channel
      // TODO: Cache membership lookups in Redis when implementing multi-instance support
      if (!channelMemberRepository.existsByIdChannelIdAndIdUserId(channelId, senderUserId)) {
        LOG.warn(
            "User {} attempted to send message to channel {} but is not a member",
            userId,
            channelId);
        sendErrorResponse(session, "You are not a member of this channel");
        return;
      }

      // Create and persist message to database
      Message messageToSave = Message.create(channelId, senderUserId, incomingMessage.text());
      Message savedMessage = messageService.saveMessage(messageToSave);

      LOG.info(
          "Persisted message {} to channel {} from user {}",
          savedMessage.messageId(),
          savedMessage.channelId(),
          userId);

      // Get channel members for targeted broadcast
      // TODO: Cache member lists in Redis when implementing multi-instance support
      List<ChannelMember> channelMembers = channelMemberRepository.findByIdChannelId(channelId);
      Set<String> memberUserIds =
          channelMembers.stream()
              .map(member -> member.userId().toString())
              .filter(memberId -> !memberId.equals(userId)) // Exclude sender
              .collect(Collectors.toSet());

      // For now, broadcast locally to channel members on this server instance
      String broadcastPayload =
          objectMapper.writeValueAsString(BroadcastMessage.fromMessage(savedMessage));
      userConnRegistry.broadcastPayloadToTargets(broadcastPayload, memberUserIds);

    } catch (JsonProcessingException e) {
      LOG.error("Failed to parse message JSON from user {}: {}", userId, message, e);
      sendErrorResponse(
          session,
          "Invalid message format. Expected JSON: {\"channelId\":\"<uuid>\",\"text\":\"<message>\"}");
    } catch (MessagePersistenceException e) {
      // Error already logged by MessageService with full context
      sendErrorResponse(session, "Failed to send message. Please try again.");
    } catch (Exception e) {
      LOG.error("Unexpected error processing message from user {}: {}", userId, message, e);
      sendErrorResponse(session, "Failed to send message. Please try again.");
    }
  }

  private void sendErrorResponse(WebSocketSession session, String errorMessage) {
    try {
      String errorPayload = objectMapper.writeValueAsString(ErrorMessage.create(errorMessage));
      session.sendAsync(errorPayload);
    } catch (JsonProcessingException e) {
      LOG.error("Failed to serialize error message: {}", errorMessage, e);
    }
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
}
