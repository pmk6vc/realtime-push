package messaging.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import jakarta.inject.Singleton;
import java.time.Duration;

/** Per-user rate limiting using token bucket algorithm with automatic cleanup. */
@Singleton
public class RateLimitService {

  private static final Duration BUCKET_EXPIRY = Duration.ofMinutes(5);

  private final Cache<String, Bucket> userBuckets;
  private final int messagesPerSecond;

  public RateLimitService(RateLimitConfig config) {
    this.messagesPerSecond = config.getMessagesPerSecond();
    this.userBuckets = Caffeine.newBuilder().expireAfterAccess(BUCKET_EXPIRY).build();
  }

  /**
   * Attempts to consume one token for the given user.
   *
   * @param userId the user identifier
   * @return true if within rate limit, false if rate limit exceeded
   */
  public boolean tryConsume(String userId) {
    Bucket bucket = userBuckets.get(userId, this::createBucket);
    return bucket.tryConsume(1);
  }

  private Bucket createBucket(String userId) {
    Bandwidth limit =
        Bandwidth.builder()
            .capacity(messagesPerSecond)
            .refillGreedy(messagesPerSecond, Duration.ofSeconds(1))
            .build();
    return Bucket.builder().addLimit(limit).build();
  }
}
