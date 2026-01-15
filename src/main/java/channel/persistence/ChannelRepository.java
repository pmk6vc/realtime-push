package channel.persistence;

import io.micronaut.data.annotation.Query;
import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.query.builder.sql.Dialect;
import io.micronaut.data.repository.CrudRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@JdbcRepository(dialect = Dialect.POSTGRES)
public interface ChannelRepository extends CrudRepository<Channel, UUID> {

  Optional<Channel> findByChannelId(UUID channelId);

  /** Finds all channels a user is a member of, with membership details, in a single query. */
  @Query(
      "SELECT c.channel_id, c.channel_name, c.owner_user_id, c.created_at, cm.joined_at "
          + "FROM channels c "
          + "JOIN channel_members cm ON c.channel_id = cm.channel_id "
          + "WHERE cm.user_id = :userId "
          + "ORDER BY cm.joined_at DESC")
  List<UserChannelProjection> findChannelsByUserId(UUID userId);
}
