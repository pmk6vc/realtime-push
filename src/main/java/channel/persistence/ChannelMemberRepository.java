package channel.persistence;

import io.micronaut.data.annotation.Query;
import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.query.builder.sql.Dialect;
import io.micronaut.data.repository.CrudRepository;
import java.util.List;
import java.util.UUID;

@JdbcRepository(dialect = Dialect.POSTGRES)
public interface ChannelMemberRepository extends CrudRepository<ChannelMember, ChannelMemberId> {

  /** Find all members of a channel. */
  List<ChannelMember> findByIdChannelId(UUID channelId);

  /** Find all channels a user belongs to. */
  List<ChannelMember> findByIdUserId(UUID userId);

  /** Check if a user is a member of a channel. */
  boolean existsByIdChannelIdAndIdUserId(UUID channelId, UUID userId);

  /** Remove a user from a channel. */
  void deleteByIdChannelIdAndIdUserId(UUID channelId, UUID userId);

  /**
   * Adds a member to a channel if they are not already a member. Uses INSERT ON CONFLICT DO
   * NOTHING to handle concurrent requests safely.
   */
  @Query(
      "INSERT INTO channel_members (channel_id, user_id) VALUES (:channelId, :userId) "
          + "ON CONFLICT DO NOTHING")
  void addMemberIfAbsent(UUID channelId, UUID userId);
}
