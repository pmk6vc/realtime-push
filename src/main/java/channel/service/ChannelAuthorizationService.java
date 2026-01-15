package channel.service;

import channel.persistence.ChannelMemberRepository;
import channel.persistence.ChannelRepository;
import jakarta.inject.Singleton;
import java.util.UUID;
import util.exception.ForbiddenException;

/** Service for checking channel authorization. */
@Singleton
public class ChannelAuthorizationService {

  private final ChannelRepository channelRepository;
  private final ChannelMemberRepository memberRepository;

  public ChannelAuthorizationService(
      ChannelRepository channelRepository, ChannelMemberRepository memberRepository) {
    this.channelRepository = channelRepository;
    this.memberRepository = memberRepository;
  }

  /** Returns true if the user is the owner of the channel. */
  public boolean isOwner(UUID channelId, UUID userId) {
    return channelRepository
        .findByChannelId(channelId)
        .map(channel -> channel.ownerUserId().equals(userId))
        .orElse(false);
  }

  /** Returns true if the user is a member of the channel. */
  public boolean isMember(UUID channelId, UUID userId) {
    return memberRepository.existsByIdChannelIdAndIdUserId(channelId, userId);
  }

  /** Throws ForbiddenException if user is not the channel owner. */
  public void requireOwner(UUID channelId, UUID userId) {
    if (!isOwner(channelId, userId)) {
      throw new ForbiddenException("Only the channel owner can perform this action");
    }
  }

  /** Throws ForbiddenException if user is not a member of the channel. */
  public void requireMember(UUID channelId, UUID userId) {
    if (!isMember(channelId, userId)) {
      throw new ForbiddenException("You must be a member of this channel");
    }
  }
}
