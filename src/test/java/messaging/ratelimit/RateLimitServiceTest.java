package messaging.ratelimit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

/** Unit tests for RateLimitService. */
class RateLimitServiceTest {

  private RateLimitService createService(int messagesPerSecond) {
    RateLimitConfig config = mock(RateLimitConfig.class);
    when(config.getMessagesPerSecond()).thenReturn(messagesPerSecond);
    return new RateLimitService(config);
  }

  @Test
  void tryConsume_succeedsWithinLimit() {
    RateLimitService service = createService(5);

    // Should allow 5 messages
    for (int i = 0; i < 5; i++) {
      assertTrue(service.tryConsume("user1"), "Message " + (i + 1) + " should succeed");
    }
  }

  @Test
  void tryConsume_failsWhenLimitExceeded() {
    RateLimitService service = createService(3);

    // Use up the limit
    assertTrue(service.tryConsume("user1"));
    assertTrue(service.tryConsume("user1"));
    assertTrue(service.tryConsume("user1"));

    // Next one should fail
    assertFalse(service.tryConsume("user1"), "Should fail after limit exceeded");
  }

  @Test
  void tryConsume_differentUsersHaveIndependentLimits() {
    RateLimitService service = createService(2);

    // User1 uses their limit
    assertTrue(service.tryConsume("user1"));
    assertTrue(service.tryConsume("user1"));
    assertFalse(service.tryConsume("user1"), "User1 should be rate limited");

    // User2 should still have their full limit
    assertTrue(service.tryConsume("user2"), "User2 should not be affected by user1's limit");
    assertTrue(service.tryConsume("user2"));
    assertFalse(service.tryConsume("user2"), "User2 should now be rate limited");
  }

  @Test
  void tryConsume_bucketsRefillOverTime() throws InterruptedException {
    RateLimitService service = createService(10);

    // Use up the limit
    for (int i = 0; i < 10; i++) {
      assertTrue(service.tryConsume("user1"));
    }
    assertFalse(service.tryConsume("user1"), "Should be rate limited");

    // Wait for bucket to refill (tokens refill at 10/second, so ~100ms per token)
    Thread.sleep(150);

    // Should be able to send at least one more message
    assertTrue(service.tryConsume("user1"), "Should have refilled after waiting");
  }
}
