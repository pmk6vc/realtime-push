package testutils;

import io.micronaut.context.annotation.Replaces;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import user.persistence.User;
import user.persistence.UserRepository;

/**
 * In-memory mock of UserRepository for component tests. Stores users in a ConcurrentHashMap to
 * support basic operations without a database.
 */
@Singleton
@Replaces(UserRepository.class)
@Requires(env = "test")
public class MockUserRepository implements UserRepository {

  private final Map<UUID, User> users = new ConcurrentHashMap<>();

  @Override
  public void ensureExists(UUID userId) {
    users.computeIfAbsent(userId, User::create);
  }

  @Override
  public User save(User entity) {
    users.put(entity.userId(), entity);
    return entity;
  }

  @Override
  public <S extends User> S update(S entity) {
    users.put(entity.userId(), entity);
    return entity;
  }

  @Override
  public <S extends User> List<S> saveAll(Iterable<S> entities) {
    entities.forEach(e -> users.put(e.userId(), e));
    return List.of();
  }

  @Override
  public <S extends User> List<S> updateAll(Iterable<S> entities) {
    entities.forEach(e -> users.put(e.userId(), e));
    return List.of();
  }

  @Override
  public Optional<User> findById(UUID id) {
    return Optional.ofNullable(users.get(id));
  }

  @Override
  public boolean existsById(UUID id) {
    return users.containsKey(id);
  }

  @Override
  public List<User> findAll() {
    return List.copyOf(users.values());
  }

  @Override
  public long count() {
    return users.size();
  }

  @Override
  public void deleteById(UUID id) {
    users.remove(id);
  }

  @Override
  public void delete(User entity) {
    users.remove(entity.userId());
  }

  @Override
  public void deleteAll(Iterable<? extends User> entities) {
    entities.forEach(e -> users.remove(e.userId()));
  }

  @Override
  public void deleteAll() {
    users.clear();
  }
}