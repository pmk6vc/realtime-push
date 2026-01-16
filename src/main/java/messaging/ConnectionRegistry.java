package messaging;

import io.micronaut.scheduling.annotation.Scheduled;
import io.micronaut.websocket.CloseReason;
import io.micronaut.websocket.WebSocketSession;
import jakarta.inject.Singleton;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Singleton
public class ConnectionRegistry {

  private final ConcurrentHashMap<String, WebSocketSession> userSessionMap =
      new ConcurrentHashMap<>();

  /**
   * Tracks closed sessions seen in the previous cleanup cycle. We store the actual session object
   * (not just userId) to avoid false positives when a user reconnects with a new session between
   * cycles. Only if we see the exact same closed session object in consecutive cycles do we
   * conclude that @OnClose failed to fire.
   */
  private final ConcurrentHashMap<String, WebSocketSession> previouslySeenClosedSessions =
      new ConcurrentHashMap<>();

  private static final Logger LOG = LoggerFactory.getLogger(ConnectionRegistry.class);

  public void registerUserSession(String userId, WebSocketSession session) {
    Optional<WebSocketSession> prevSession =
        Optional.ofNullable(userSessionMap.put(userId, session));
    LOG.debug("Registered session for userId {}: {}", userId, session.getId());
    prevSession.ifPresent(
        prev -> {
          if (prev != session && prev.isOpen()) {
            prev.close(
                new CloseReason(CloseReason.NORMAL.getCode(), "Replaced by a new connection"));
            LOG.debug("Closed previous session for userId {}: {}", userId, prev.getId());
          }
        });
  }

  public void removeUserSession(String userId, WebSocketSession session) {
    LOG.debug("Removing session for userId {}: {}", userId, session.getId());
    userSessionMap.remove(userId, session);
  }

  public void broadcastPayload(
      String payload, Optional<Set<String>> targetUserSet, Optional<Set<String>> excludedUserSet) {
    userSessionMap.forEach(
        (uid, registeredSession) -> {
          if (targetUserSet.map(set -> !set.contains(uid)).orElse(false)) return;
          if (excludedUserSet.map(set -> set.contains(uid)).orElse(false)) return;
          if (!registeredSession.isOpen()) return;
          registeredSession
              .sendAsync(payload)
              .exceptionally(
                  ex -> {
                    LOG.error("Failed to send payload to userId {}", uid, ex);
                    return null;
                  });
        });
  }

  public void broadcastPayloadWithExclusions(String payload, Set<String> excludedUserSet) {
    broadcastPayload(payload, Optional.empty(), Optional.of(excludedUserSet));
  }

  public void broadcastPayloadToTargets(String payload, Set<String> targetUserSet) {
    broadcastPayload(payload, Optional.of(targetUserSet), Optional.empty());
  }

  /** Returns the number of currently registered sessions (includes both open and closed). */
  public int getRegisteredSessionCount() {
    return userSessionMap.size();
  }

  /** Returns the number of currently open sessions. */
  public int getActiveSessionCount() {
    return (int) userSessionMap.values().stream().filter(WebSocketSession::isOpen).count();
  }

  /**
   * Periodically cleans up closed sessions that were not removed by @OnClose.
   *
   * <p><b>Why this exists:</b> In normal operation, this cleanup should not be necessary.
   * Micronaut's WebSocket implementation (via Netty) should reliably trigger @OnClose for all
   * disconnection types, including network partitions and client crashes. However, we implement
   * this as a defensive measure against hypothetical edge cases where @OnClose might not fire.
   *
   * <p><b>Two-cycle detection:</b> To avoid false positives, we only evict a closed session if we
   * see the exact same session object in two consecutive cleanup cycles. This ensures @OnClose has
   * had at least one full cleanup interval (default 60s) to fire. We track sessions by object
   * reference (not just userId) to handle the case where a user reconnects with a new session
   * between cycles.
   *
   * <p><b>Monitoring:</b> If this cleanup ever evicts sessions, it indicates either a framework bug
   * or an unexpected edge case that bypassed @OnClose. We log at WARN level when this happens.
   * TODO: Emit this as a Prometheus metric for production monitoring.
   */
  @Scheduled(fixedDelay = "${connection-registry.cleanup-interval:60s}")
  void cleanupClosedSessions() {
    Map<String, WebSocketSession> currentlyClosedSessions = new HashMap<>();
    int evictedCount = 0;

    for (var entry : userSessionMap.entrySet()) {
      String userId = entry.getKey();
      WebSocketSession session = entry.getValue();

      if (session.isOpen()) {
        continue;
      }

      // Session is closed - check if we saw this exact session last cycle
      WebSocketSession previouslySeen = previouslySeenClosedSessions.get(userId);
      if (previouslySeen == session) {
        // Same session was closed last cycle too - @OnClose didn't fire, evict it
        userSessionMap.remove(userId, session);
        evictedCount++;

        LOG.warn(
            "Evicted stale session for userId {} (sessionId={}). "
                + "@OnClose did not fire within cleanup interval - this may indicate a framework bug.",
            userId,
            session.getId());
      } else {
        // First time seeing this session closed - record for next cycle
        currentlyClosedSessions.put(userId, session);
      }
    }

    // Swap tracking map for next cycle
    previouslySeenClosedSessions.clear();
    previouslySeenClosedSessions.putAll(currentlyClosedSessions);

    if (evictedCount > 0) {
      LOG.warn(
          "Cleanup evicted {} stale sessions. {} active sessions remaining.",
          evictedCount,
          userSessionMap.size());
    } else {
      LOG.debug(
          "Session cleanup complete: {} active sessions, no stale sessions found",
          userSessionMap.size());
    }
  }
}
