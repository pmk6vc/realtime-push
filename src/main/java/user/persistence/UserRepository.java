package user.persistence;

import io.micronaut.data.annotation.Query;
import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.query.builder.sql.Dialect;
import io.micronaut.data.repository.CrudRepository;
import java.util.UUID;

/** Repository for User entity with upsert support. */
@JdbcRepository(dialect = Dialect.POSTGRES)
public interface UserRepository extends CrudRepository<User, UUID> {

  /**
   * Ensures a user exists in the database. Uses INSERT ON CONFLICT DO NOTHING for idempotency.
   *
   * @param userId the user ID to ensure exists
   */
  @Query("INSERT INTO users (user_id) VALUES (:userId) ON CONFLICT DO NOTHING")
  void ensureExists(UUID userId);
}
