package messaging.message;

import io.micronaut.serde.annotation.Serdeable;

/** WebSocket acknowledgment message sent to client on successful connection. */
@Serdeable
public record AckMessage(MessageType type, String userId, String sessionId) {
  public static AckMessage create(String userId, String sessionId) {
    return new AckMessage(MessageType.ACK, userId, sessionId);
  }
}
