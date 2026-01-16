package messaging.message;

import io.micronaut.serde.annotation.Serdeable;
import java.time.Instant;
import java.util.UUID;
import messaging.persistence.Message;

/** WebSocket broadcast message sent to channel members when a new message is received. */
@Serdeable
public record BroadcastMessage(
    MessageType type, UUID messageId, UUID channelId, UUID from, Instant sentAt, String text) {
  public static BroadcastMessage fromMessage(Message message) {
    return new BroadcastMessage(
        MessageType.MESSAGE,
        message.messageId(),
        message.channelId(),
        message.senderUserId(),
        message.sentAt(),
        message.body());
  }
}
