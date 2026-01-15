package user.filter;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.Filter;
import io.micronaut.http.filter.HttpServerFilter;
import io.micronaut.http.filter.ServerFilterChain;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import user.persistence.UserRepository;

/**
 * Filter that ensures authenticated users exist in the database. Runs on every request and performs
 * an idempotent INSERT ON CONFLICT DO NOTHING to create the user if they don't exist.
 *
 * <p>Uses an in-memory cache of recently-seen user IDs to skip redundant DB calls.
 */
@Filter("/**")
public class UserSyncFilter implements HttpServerFilter {

  private static final Logger LOG = LoggerFactory.getLogger(UserSyncFilter.class);
  private static final String USER_ID_HEADER = "X-User-Id";

  private final UserRepository userRepository;

  // Cache of recently-seen user IDs to avoid redundant DB calls
  // In production, consider using Caffeine with TTL or a distributed cache
  private final Set<UUID> seenUsers = Collections.newSetFromMap(new ConcurrentHashMap<>());

  public UserSyncFilter(UserRepository userRepository) {
    this.userRepository = userRepository;
  }

  @Override
  public Publisher<MutableHttpResponse<?>> doFilter(
      HttpRequest<?> request, ServerFilterChain chain) {
    String userIdHeader = request.getHeaders().get(USER_ID_HEADER);

    if (userIdHeader == null || userIdHeader.isBlank()) {
      // No user ID - let the request proceed (will likely fail auth downstream)
      return chain.proceed(request);
    }

    UUID userId;
    try {
      userId = UUID.fromString(userIdHeader.trim());
    } catch (IllegalArgumentException e) {
      // Invalid UUID - let the request proceed (will fail validation downstream)
      return chain.proceed(request);
    }

    // Check cache first
    if (!seenUsers.contains(userId)) {
      // User not in cache - ensure they exist in DB (synchronous, fast operation)
      try {
        userRepository.ensureExists(userId);
        seenUsers.add(userId);
      } catch (Exception e) {
        LOG.warn("Failed to ensure user {} exists in database: {}", userId, e.getMessage());
        // Don't fail the request - let it proceed and fail on FK constraint if needed
      }
    }

    return chain.proceed(request);
  }
}