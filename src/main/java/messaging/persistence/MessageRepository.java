package messaging.persistence;

import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.query.builder.sql.Dialect;
import io.micronaut.data.repository.CrudRepository;

@JdbcRepository(dialect = Dialect.POSTGRES)
public interface MessageRepository extends CrudRepository<Message, MessageId> {
  // Micronaut Data automatically generates the implementation at compile-time
  // Composite primary key (channel_id, message_id) represented by MessageId
  // save() method is provided by CrudRepository
}
