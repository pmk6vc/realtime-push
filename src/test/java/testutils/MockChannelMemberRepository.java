package testutils;

import channel.persistence.ChannelMember;
import channel.persistence.ChannelMemberId;
import channel.persistence.ChannelMemberRepository;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory mock of ChannelMemberRepository for component tests. Pre-seeds Alice and Bob as members
 * of the test channel.
 */
@Singleton
@Replaces(ChannelMemberRepository.class)
@Requires(env = "test")
public class MockChannelMemberRepository implements ChannelMemberRepository {

  private static final UUID TEST_CHANNEL_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final UUID ALICE_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID BOB_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

  // Map of channelId -> Set of userIds
  private final Map<UUID, Set<UUID>> channelMembers = new ConcurrentHashMap<>();

  public MockChannelMemberRepository() {
    // Pre-seed test channel with Alice and Bob as members
    channelMembers.put(
        TEST_CHANNEL_ID, ConcurrentHashMap.newKeySet(Set.of(ALICE_ID, BOB_ID).size()));
    channelMembers.get(TEST_CHANNEL_ID).add(ALICE_ID);
    channelMembers.get(TEST_CHANNEL_ID).add(BOB_ID);
  }

  @Override
  public boolean existsByIdChannelIdAndIdUserId(UUID channelId, UUID userId) {
    Set<UUID> members = channelMembers.get(channelId);
    return members != null && members.contains(userId);
  }

  @Override
  public List<ChannelMember> findByIdChannelId(UUID channelId) {
    Set<UUID> members = channelMembers.get(channelId);
    if (members == null) {
      return List.of();
    }
    return members.stream()
        .map(userId -> new ChannelMember(new ChannelMemberId(channelId, userId), Instant.now()))
        .toList();
  }

  @Override
  public List<ChannelMember> findByIdUserId(UUID userId) {
    return channelMembers.entrySet().stream()
        .filter(entry -> entry.getValue().contains(userId))
        .map(entry -> new ChannelMember(new ChannelMemberId(entry.getKey(), userId), Instant.now()))
        .toList();
  }

  @Override
  public void deleteByIdChannelIdAndIdUserId(UUID channelId, UUID userId) {
    Set<UUID> members = channelMembers.get(channelId);
    if (members != null) {
      members.remove(userId);
    }
  }

  @Override
  public void addMemberIfAbsent(UUID channelId, UUID userId) {
    channelMembers.computeIfAbsent(channelId, k -> ConcurrentHashMap.newKeySet()).add(userId);
  }

  @Override
  public ChannelMember save(ChannelMember entity) {
    addMemberIfAbsent(entity.channelId(), entity.userId());
    return entity;
  }

  @Override
  public <S extends ChannelMember> S update(S entity) {
    return entity;
  }

  @Override
  public <S extends ChannelMember> List<S> saveAll(Iterable<S> entities) {
    entities.forEach(this::save);
    return List.of();
  }

  @Override
  public <S extends ChannelMember> List<S> updateAll(Iterable<S> entities) {
    return List.of();
  }

  @Override
  public Optional<ChannelMember> findById(ChannelMemberId id) {
    if (existsByIdChannelIdAndIdUserId(id.channelId(), id.userId())) {
      return Optional.of(new ChannelMember(id, Instant.now()));
    }
    return Optional.empty();
  }

  @Override
  public boolean existsById(ChannelMemberId id) {
    return existsByIdChannelIdAndIdUserId(id.channelId(), id.userId());
  }

  @Override
  public List<ChannelMember> findAll() {
    return channelMembers.entrySet().stream()
        .flatMap(
            entry ->
                entry.getValue().stream()
                    .map(
                        userId ->
                            new ChannelMember(
                                new ChannelMemberId(entry.getKey(), userId), Instant.now())))
        .toList();
  }

  @Override
  public long count() {
    return channelMembers.values().stream().mapToLong(Set::size).sum();
  }

  @Override
  public void deleteById(ChannelMemberId id) {
    deleteByIdChannelIdAndIdUserId(id.channelId(), id.userId());
  }

  @Override
  public void delete(ChannelMember entity) {
    deleteByIdChannelIdAndIdUserId(entity.channelId(), entity.userId());
  }

  @Override
  public void deleteAll(Iterable<? extends ChannelMember> entities) {
    entities.forEach(this::delete);
  }

  @Override
  public void deleteAll() {
    channelMembers.clear();
  }
}
