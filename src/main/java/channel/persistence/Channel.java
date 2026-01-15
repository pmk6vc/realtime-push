package channel.persistence;

import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;
import io.micronaut.data.annotation.MappedProperty;
import io.micronaut.data.model.DataType;
import java.time.Instant;
import java.util.UUID;

@MappedEntity("channels")
public record Channel(
    @Id @MappedProperty("channel_id") UUID channelId,
    @MappedProperty("channel_name") String channelName,
    @MappedProperty("owner_user_id") UUID ownerUserId,
    @MappedProperty(value = "created_at", type = DataType.TIMESTAMP) Instant createdAt) {

  /** Creates a new channel with generated ID and current timestamp. */
  public static Channel create(String channelName, UUID ownerUserId) {
    return new Channel(UUID.randomUUID(), channelName, ownerUserId, Instant.now());
  }
}
