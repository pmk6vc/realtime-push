package messaging.ratelimit;

import io.micronaut.context.annotation.ConfigurationProperties;

/** Configuration properties for rate limiting. */
@ConfigurationProperties("messaging.rate-limit")
public class RateLimitConfig {

  private int messagesPerSecond = 10;

  public int getMessagesPerSecond() {
    return messagesPerSecond;
  }

  public void setMessagesPerSecond(int messagesPerSecond) {
    this.messagesPerSecond = messagesPerSecond;
  }
}
