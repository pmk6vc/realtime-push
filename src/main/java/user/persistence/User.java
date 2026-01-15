package user.persistence;

import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;
import io.micronaut.data.annotation.MappedProperty;
import io.micronaut.data.model.DataType;
import java.time.Instant;
import java.util.UUID;

/** User entity representing a row in the users table. */
@MappedEntity("users")
public record User(
    @Id @MappedProperty("user_id") UUID userId,
    @MappedProperty(value = "created_at", type = DataType.TIMESTAMP) Instant createdAt) {

  /** Creates a new user with current timestamp. */
  public static User create(UUID userId) {
    return new User(userId, Instant.now());
  }
}