// java
package messaging;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.micronaut.websocket.CloseReason;
import io.micronaut.websocket.WebSocketSession;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ConnectionRegistryTest {

  @Captor ArgumentCaptor<CloseReason> closeReasonCaptor;

  @Test
  void registeringNewSessionForSameUserClosesPreviousOpenSession() {
    ConnectionRegistry registry = new ConnectionRegistry();
    WebSocketSession prev = mock(WebSocketSession.class);
    when(prev.getId()).thenReturn("prev-sess");
    when(prev.isOpen()).thenReturn(true);
    registry.registerUserSession("alice", prev);

    WebSocketSession next = mock(WebSocketSession.class);
    when(next.getId()).thenReturn("next-sess");
    registry.registerUserSession("alice", next);

    verify(prev, times(1)).close(closeReasonCaptor.capture());
    CloseReason cr = closeReasonCaptor.getValue();
    assertEquals(CloseReason.NORMAL.getCode(), cr.getCode());
    assertTrue(cr.getReason().toLowerCase().contains("replaced"));
  }

  @Test
  void broadcastPayloadWithExclusions_sendsToNonExcludedOpenSessions() {
    ConnectionRegistry registry = new ConnectionRegistry();

    // excluded user (should NOT receive)
    WebSocketSession alice = mock(WebSocketSession.class);
    when(alice.getId()).thenReturn("s-alice");
    registry.registerUserSession("alice", alice);

    // target user 1 (should receive)
    WebSocketSession bob = mock(WebSocketSession.class);
    when(bob.getId()).thenReturn("s-bob");
    when(bob.isOpen()).thenReturn(true);
    when(bob.sendAsync(anyString())).thenReturn(CompletableFuture.completedFuture(null));
    registry.registerUserSession("bob", bob);

    // target user 2 (closed - should NOT receive)
    WebSocketSession dave = mock(WebSocketSession.class);
    when(dave.getId()).thenReturn("s-dave");
    when(dave.isOpen()).thenReturn(false);
    registry.registerUserSession("dave", dave);

    String payload = "{\"type\":\"test\",\"text\":\"hello\"}";

    // broadcast excluding alice; expect only bob (open & not excluded) to receive
    registry.broadcastPayloadWithExclusions(payload, Set.of("alice"));

    verify(bob, times(1)).sendAsync(payload);
    verify(alice, never()).sendAsync(anyString());
    verify(dave, never()).sendAsync(anyString());
  }
}
