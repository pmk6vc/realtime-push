package channel.service;

import channel.persistence.Channel;
import channel.persistence.ChannelMember;
import channel.persistence.ChannelMemberRepository;
import channel.persistence.ChannelRepository;
import jakarta.inject.Singleton;
import jakarta.transaction.Transactional;
import java.util.UUID;
import util.exception.NotFoundException;

/** Service for channel operations. */
@Singleton
public class ChannelService {

  private final ChannelRepository channelRepository;
  private final ChannelMemberRepository memberRepository;
  private final ChannelAuthorizationService authService;

  public ChannelService(
      ChannelRepository channelRepository,
      ChannelMemberRepository memberRepository,
      ChannelAuthorizationService authService) {
    this.channelRepository = channelRepository;
    this.memberRepository = memberRepository;
    this.authService = authService;
  }

  /** Creates a new channel. The creator becomes the owner and first member. */
  @Transactional
  public Channel createChannel(String channelName, UUID ownerUserId) {
    Channel channel = Channel.create(channelName, ownerUserId);
    Channel saved = channelRepository.save(channel);

    // Owner is automatically a member
    ChannelMember ownerMembership = ChannelMember.create(saved.channelId(), ownerUserId);
    memberRepository.save(ownerMembership);

    return saved;
  }

  /** Gets a channel. Only members can view channel details. */
  public Channel getChannel(UUID channelId, UUID requestingUserId) {
    Channel channel =
        channelRepository
            .findByChannelId(channelId)
            .orElseThrow(() -> new NotFoundException("Channel not found"));

    authService.requireMember(channelId, requestingUserId);
    return channel;
  }

  /** Updates a channel. Only the owner can update. */
  @Transactional
  public Channel updateChannel(UUID channelId, String newName, UUID requestingUserId) {
    Channel channel =
        channelRepository
            .findByChannelId(channelId)
            .orElseThrow(() -> new NotFoundException("Channel not found"));

    authService.requireOwner(channelId, requestingUserId);

    Channel updated =
        new Channel(channel.channelId(), newName, channel.ownerUserId(), channel.createdAt());
    return channelRepository.update(updated);
  }

  /** Deletes a channel. Only the owner can delete. Memberships cascade-delete. */
  @Transactional
  public void deleteChannel(UUID channelId, UUID requestingUserId) {
    if (!channelRepository.existsById(channelId)) {
      throw new NotFoundException("Channel not found");
    }

    authService.requireOwner(channelId, requestingUserId);
    channelRepository.deleteById(channelId);
  }
}
