package channel.persistence;

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
}
