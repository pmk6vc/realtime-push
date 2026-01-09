package testutils;

import io.micronaut.context.annotation.Replaces;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import messaging.Message;
import messaging.MessageRepository;

@Singleton
@Replaces(MessageRepository.class)
@Requires(env = "test")
public class MockMessageRepository extends MessageRepository {

  public MockMessageRepository() {
    super(null); // No DataSource needed for mock
  }

  @Override
  public void insert(Message message) {
    // No-op for unit tests - we're only testing behavior, not persistence
  }
}
