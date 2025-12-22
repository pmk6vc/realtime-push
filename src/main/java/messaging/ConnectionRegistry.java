package messaging;

import io.micronaut.websocket.CloseReason;
import io.micronaut.websocket.WebSocketSession;
import jakarta.inject.Singleton;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Singleton
public class ConnectionRegistry {

  private final ConcurrentHashMap<String, WebSocketSession> userSessionMap =
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
}
