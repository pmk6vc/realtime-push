package messaging;

import java.time.Instant;
import java.util.UUID;

public record Message(
    UUID channelId, UUID messageId, UUID senderUserId, Instant sentAt, String body) {

  public static Message create(UUID channelId, UUID senderUserId, String body) {
    return new Message(channelId, UUID.randomUUID(), senderUserId, Instant.now(), body);
  }
}
