package channel.persistence;

import io.micronaut.data.annotation.EmbeddedId;
import io.micronaut.data.annotation.MappedEntity;
import io.micronaut.data.annotation.MappedProperty;
import io.micronaut.data.model.DataType;
import java.time.Instant;
import java.util.UUID;

@MappedEntity("channel_members")
public record ChannelMember(
    @EmbeddedId ChannelMemberId id,
    @MappedProperty(value = "joined_at", type = DataType.TIMESTAMP) Instant joinedAt) {

  /** Convenience accessor for channel ID. */
  public UUID channelId() {
    return id.channelId();
  }

  /** Convenience accessor for user ID. */
  public UUID userId() {
    return id.userId();
  }

  /** Creates a new channel membership with current timestamp. */
  public static ChannelMember create(UUID channelId, UUID userId) {
    return new ChannelMember(new ChannelMemberId(channelId, userId), Instant.now());
  }
}
