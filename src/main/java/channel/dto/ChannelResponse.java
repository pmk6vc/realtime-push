package channel.dto;

import channel.persistence.Channel;
import io.micronaut.serde.annotation.Serdeable;
import java.time.Instant;
import java.util.UUID;

/** Response body for channel operations. */
@Serdeable
public record ChannelResponse(
    UUID channelId, String channelName, UUID ownerUserId, Instant createdAt) {

  public static ChannelResponse from(Channel channel) {
    return new ChannelResponse(
        channel.channelId(), channel.channelName(), channel.ownerUserId(), channel.createdAt());
  }
}
