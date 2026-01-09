package messaging;

import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.query.builder.sql.Dialect;
import io.micronaut.data.repository.CrudRepository;
import java.util.UUID;

@JdbcRepository(dialect = Dialect.POSTGRES)
public interface MessageRepository extends CrudRepository<Message, UUID> {
  // Micronaut Data automatically generates the implementation at compile-time
  // No need to write any SQL - save() method is provided by CrudRepository
}
