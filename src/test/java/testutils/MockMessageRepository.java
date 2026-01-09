package testutils;

import io.micronaut.context.annotation.Replaces;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import messaging.Message;
import messaging.MessageRepository;

@Singleton
@Replaces(MessageRepository.class)
@Requires(env = "test")
public class MockMessageRepository implements MessageRepository {

  @Override
  public <S extends Message> S save(S entity) {
    // No-op for unit tests - we're only testing behavior, not persistence
    return entity;
  }

  @Override
  public <S extends Message> S update(S entity) {
    return entity;
  }

  @Override
  public <S extends Message> List<S> saveAll(Iterable<S> entities) {
    return List.of();
  }

  @Override
  public <S extends Message> List<S> updateAll(Iterable<S> entities) {
    return List.of();
  }

  @Override
  public Optional<Message> findById(UUID uuid) {
    return Optional.empty();
  }

  @Override
  public boolean existsById(UUID uuid) {
    return false;
  }

  @Override
  public List<Message> findAll() {
    return List.of();
  }

  @Override
  public long count() {
    return 0;
  }

  @Override
  public void deleteById(UUID uuid) {
    // No-op
  }

  @Override
  public void delete(Message entity) {
    // No-op
  }

  @Override
  public void deleteAll(Iterable<? extends Message> entities) {
    // No-op
  }

  @Override
  public void deleteAll() {
    // No-op
  }
}
