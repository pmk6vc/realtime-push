package messaging.persistence;

import io.micronaut.data.annotation.EmbeddedId;
import io.micronaut.data.annotation.MappedEntity;
import io.micronaut.data.annotation.MappedProperty;
import io.micronaut.data.model.DataType;
import java.time.Instant;
import java.util.UUID;

@MappedEntity("messages")
public record Message(
    @EmbeddedId MessageId id,
    @MappedProperty("sender_user_id") UUID senderUserId,
    @MappedProperty(value = "sent_at", type = DataType.TIMESTAMP) Instant sentAt,
    String body) {

  // Convenience accessors for ID components
  public UUID channelId() {
    return id.channelId();
  }

  public UUID messageId() {
    return id.messageId();
  }

  /**
   * Creates a new message with application-generated timestamp.
   *
   * <p>Uses Instant.now() which: - Returns UTC timestamp (no timezone conversion needed) - Uses
   * system clock (should be NTP-synced in production) - Gives application full control over
   * timestamp generation
   */
  public static Message create(UUID channelId, UUID senderUserId, String body) {
    MessageId id = new MessageId(channelId, UUID.randomUUID());
    return new Message(id, senderUserId, Instant.now(), body);
  }
}
