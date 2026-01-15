package channel.dto;

import channel.persistence.Channel;
import io.micronaut.serde.annotation.Serdeable;
import java.time.Instant;
import java.util.UUID;

/** Response representing a user's membership in a channel. */
@Serdeable
public record ChannelMembershipResponse(
    UUID channelId, String channelName, UUID ownerUserId, Instant joinedAt) {

  /** Creates a ChannelMembershipResponse from a Channel and join timestamp. */
  public static ChannelMembershipResponse from(Channel channel, Instant joinedAt) {
    return new ChannelMembershipResponse(
        channel.channelId(), channel.channelName(), channel.ownerUserId(), joinedAt);
  }
}
