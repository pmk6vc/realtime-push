package messaging;

import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;
import io.micronaut.data.annotation.MappedProperty;
import io.micronaut.data.model.DataType;
import java.time.Instant;
import java.util.UUID;

@MappedEntity("messages")
public record Message(
    @MappedProperty("channel_id") UUID channelId,
    @Id @MappedProperty("message_id") UUID messageId,
    @MappedProperty("sender_user_id") UUID senderUserId,
    @MappedProperty(value = "sent_at", type = DataType.TIMESTAMP) Instant sentAt,
    String body) {

  public static Message create(UUID channelId, UUID senderUserId, String body) {
    return new Message(channelId, UUID.randomUUID(), senderUserId, Instant.now(), body);
  }
}
