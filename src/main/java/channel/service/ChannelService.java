package channel.service;

import channel.persistence.Channel;
import channel.persistence.ChannelMember;
import channel.persistence.ChannelMemberRepository;
import channel.persistence.ChannelRepository;
import jakarta.inject.Singleton;
import jakarta.transaction.Transactional;
import java.util.UUID;
import user.persistence.UserRepository;
import util.exception.NotFoundException;

/** Service for channel operations. */
@Singleton
public class ChannelService {

  private final ChannelRepository channelRepository;
  private final ChannelMemberRepository memberRepository;
  private final ChannelAuthorizationService authService;
  private final UserRepository userRepository;

  public ChannelService(
      ChannelRepository channelRepository,
      ChannelMemberRepository memberRepository,
      ChannelAuthorizationService authService,
      UserRepository userRepository) {
    this.channelRepository = channelRepository;
    this.memberRepository = memberRepository;
    this.authService = authService;
    this.userRepository = userRepository;
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

  /**
   * Adds a member to a channel. Only the owner can add members. Idempotent - adding an existing
   * member succeeds without error.
   */
  @Transactional
  public void addMember(UUID channelId, UUID memberUserId, UUID requestingUserId) {
    if (!channelRepository.existsById(channelId)) {
      throw new NotFoundException("Channel not found");
    }

    authService.requireOwner(channelId, requestingUserId);

    // Ensure the target user exists in the database (for FK constraint)
    userRepository.ensureExists(memberUserId);
    memberRepository.addMemberIfAbsent(channelId, memberUserId);
  }

  /**
   * Removes a member from a channel. Only the owner can remove members. Owner cannot remove
   * themselves (must delete channel instead).
   */
  @Transactional
  public void removeMember(UUID channelId, UUID memberUserId, UUID requestingUserId) {
    Channel channel =
        channelRepository
            .findByChannelId(channelId)
            .orElseThrow(() -> new NotFoundException("Channel not found"));

    authService.requireOwner(channelId, requestingUserId);

    // Owner cannot remove themselves
    if (channel.ownerUserId().equals(memberUserId)) {
      throw new util.exception.BadRequestException(
          "Owner cannot be removed. Delete the channel instead.");
    }

    memberRepository.deleteByIdChannelIdAndIdUserId(channelId, memberUserId);
  }

  /**
   * Transfers ownership of a channel to a new owner. Only the current owner can transfer ownership.
   * The new owner must already be a member of the channel.
   *
   * @return the updated channel with the new owner
   */
  @Transactional
  public Channel transferOwnership(UUID channelId, UUID newOwnerUserId, UUID requestingUserId) {
    Channel channel =
        channelRepository
            .findByChannelId(channelId)
            .orElseThrow(() -> new NotFoundException("Channel not found"));

    authService.requireOwner(channelId, requestingUserId);

    // Cannot transfer to self
    if (channel.ownerUserId().equals(newOwnerUserId)) {
      throw new util.exception.BadRequestException("You are already the owner of this channel.");
    }

    // New owner must be a member
    authService.requireMember(channelId, newOwnerUserId);

    Channel updated =
        new Channel(
            channel.channelId(), channel.channelName(), newOwnerUserId, channel.createdAt());
    return channelRepository.update(updated);
  }
}
