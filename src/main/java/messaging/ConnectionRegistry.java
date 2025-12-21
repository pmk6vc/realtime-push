package messaging;

import io.micronaut.websocket.CloseReason;
import io.micronaut.websocket.WebSocketSession;
import jakarta.inject.Singleton;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Singleton
public class ConnectionRegistry {

    private final ConcurrentHashMap<String, WebSocketSession> userSessionMap = new ConcurrentHashMap<>();

    public void registerUserSession(String userId, WebSocketSession session) {
        Optional<WebSocketSession> prevSession = Optional.ofNullable(userSessionMap.put(userId, session));
        prevSession.ifPresent(prev -> {
            if (prev != session && prev.isOpen()) {
                prev.close(new CloseReason(CloseReason.NORMAL.getCode(), "Replaced by a new connection"));
            }
        });
    }

    public void removeUserSession(String userId, WebSocketSession session) {
        userSessionMap.remove(userId, session);
    }

    public void broadcastPayload(String payload, Optional<Set<String>> targetUserSet, Optional<Set<String>> excludedUserSet) {
        userSessionMap.forEach((uid, registeredSession) -> {
            if (targetUserSet.map(set -> !set.contains(uid)).orElse(false)) return;
            if (excludedUserSet.map(set -> set.contains(uid)).orElse(false)) return;
            if (!registeredSession.isOpen()) return;
            // TODO: Handle failed send (e.g., log, cleanup)
            // It's possible that session closed between check and message send
            registeredSession.sendAsync(payload).exceptionally(ex -> null);
        });
    }

    public void broadcastPayloadWithExclusions(String payload, Set<String> excludedUserSet) {
        broadcastPayload(payload, Optional.empty(), Optional.of(excludedUserSet));
    }

    public void broadcastPayloadToTargets(String payload, Set<String> targetUserSet) {
        broadcastPayload(payload, Optional.of(targetUserSet), Optional.empty());
    }
}
