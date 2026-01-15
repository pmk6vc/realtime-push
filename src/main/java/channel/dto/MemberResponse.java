package channel.dto;

import channel.persistence.ChannelMember;
import io.micronaut.serde.annotation.Serdeable;
import java.time.Instant;
import java.util.UUID;

/** Response representing a channel member. */
@Serdeable
public record MemberResponse(UUID userId, Instant joinedAt) {

  /** Creates a MemberResponse from a ChannelMember entity. */
  public static MemberResponse from(ChannelMember member) {
    return new MemberResponse(member.userId(), member.joinedAt());
  }
}
