package messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.websocket.CloseReason;
import io.micronaut.websocket.WebSocketSession;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.junit.jupiter.MockitoExtension;

@MicronautTest
@ExtendWith(MockitoExtension.class)
class ConnectionRegistryTest {

  @Captor ArgumentCaptor<CloseReason> closeReasonCaptor;

  @Test
  void registerNewSession_newSessionForSameUserClosesPreviousOpenSession() {
    ConnectionRegistry registry = new ConnectionRegistry();
    WebSocketSession prev = mock(WebSocketSession.class);
    when(prev.getId()).thenReturn("prev-sess");
    when(prev.isOpen()).thenReturn(true);
    registry.registerUserSession("alice", prev);

    WebSocketSession next = mock(WebSocketSession.class);
    when(next.getId()).thenReturn("next-sess");
    registry.registerUserSession("alice", next);

    verify(prev).close(closeReasonCaptor.capture());
    CloseReason cr = closeReasonCaptor.getValue();
    assertEquals(CloseReason.NORMAL.getCode(), cr.getCode());
    assertTrue(cr.getReason().toLowerCase().contains("replaced"));
  }

  @Test
  void registerNewSession_newSessionForSameUserIgnoresPreviousClosedSession() {
    ConnectionRegistry registry = new ConnectionRegistry();
    WebSocketSession prev = mock(WebSocketSession.class);
    when(prev.getId()).thenReturn("prev-sess");
    when(prev.isOpen()).thenReturn(false);
    registry.registerUserSession("alice", prev);

    WebSocketSession next = mock(WebSocketSession.class);
    when(next.getId()).thenReturn("next-sess");
    registry.registerUserSession("alice", next);

    verify(prev, never()).close(any());
  }

  @Test
  void removeUserSession_removeOnlyWorksOnSessionMatch() {
    // Register s1 to alice
    ConnectionRegistry registry = new ConnectionRegistry();
    WebSocketSession s1 = mock(WebSocketSession.class);
    WebSocketSession s2 = mock(WebSocketSession.class);
    when(s1.isOpen()).thenReturn(true);
    registry.registerUserSession("alice", s1);

    // Remove s2 for alice from registry (should be no-op) and broadcast message
    registry.removeUserSession("alice", s2);
    when(s1.sendAsync("p")).thenReturn(CompletableFuture.completedFuture(null));
    registry.broadcastPayloadToTargets("p", Set.of("alice"));

    // Confirm s1 still receives the message
    verify(s1, times(1)).sendAsync("p");
    verify(s2, never()).sendAsync(anyString());

    // Removing with the correct session should stop future sends
    registry.removeUserSession("alice", s1);
    registry.broadcastPayloadToTargets("p2", Set.of("alice"));
    verify(s1, never()).sendAsync("p2");
  }

  @Test
  void broadcastPayloadWithExclusions_sendsToNonExcludedOpenSessions() {
    WebSocketSession alice = mock(WebSocketSession.class);
    WebSocketSession bob = mock(WebSocketSession.class);
    when(bob.isOpen()).thenReturn(true);
    when(bob.sendAsync(anyString())).thenReturn(CompletableFuture.completedFuture(null));

    WebSocketSession carol = mock(WebSocketSession.class);
    when(carol.isOpen()).thenReturn(true);
    when(carol.sendAsync(anyString())).thenReturn(CompletableFuture.completedFuture(null));

    WebSocketSession dave = mock(WebSocketSession.class);
    when(dave.isOpen()).thenReturn(false);

    ConnectionRegistry registry = new ConnectionRegistry();
    registry.registerUserSession("alice", alice);
    registry.registerUserSession("bob", bob);
    registry.registerUserSession("carol", carol);
    registry.registerUserSession("dave", dave);
    registry.broadcastPayloadWithExclusions("payload", Set.of("alice"));

    verify(alice, never()).sendAsync(anyString());
    verify(bob, times(1)).sendAsync("payload");
    verify(carol, times(1)).sendAsync("payload");
    verify(dave, never()).sendAsync(anyString());
  }

  @Test
  void broadcastPayloadToTargets_SendsToTargetOpenSessions() {
    WebSocketSession alice = mock(WebSocketSession.class);

    WebSocketSession bob = mock(WebSocketSession.class);
    when(bob.isOpen()).thenReturn(true);
    when(bob.sendAsync(anyString())).thenReturn(CompletableFuture.completedFuture(null));

    WebSocketSession carol = mock(WebSocketSession.class);
    when(carol.isOpen()).thenReturn(true);
    when(carol.sendAsync(anyString())).thenReturn(CompletableFuture.completedFuture(null));

    WebSocketSession dave = mock(WebSocketSession.class);
    when(dave.isOpen()).thenReturn(false);

    ConnectionRegistry registry = new ConnectionRegistry();
    registry.registerUserSession("alice", alice);
    registry.registerUserSession("bob", bob);
    registry.registerUserSession("carol", carol);
    registry.registerUserSession("dave", dave);
    registry.broadcastPayloadToTargets("payload", Set.of("bob", "carol", "dave"));

    verify(alice, never()).sendAsync(anyString());
    verify(bob, times(1)).sendAsync("payload");
    verify(carol, times(1)).sendAsync("payload");
    verify(dave, never()).sendAsync(anyString());
  }

  @Test
  void broadcastPayload_exclusionWinsOverTargeting() {
    ConnectionRegistry registry = new ConnectionRegistry();

    WebSocketSession alice = mock(WebSocketSession.class);
    registry.registerUserSession("alice", alice);
    registry.broadcastPayload("p", Optional.of(Set.of("alice")), Optional.of(Set.of("alice")));

    verify(alice, never()).sendAsync(anyString());
  }

  @Test
  void broadcastPayload_ignoresUnregisteredUsers() {
    ConnectionRegistry registry = new ConnectionRegistry();

    WebSocketSession alice = mock(WebSocketSession.class);
    when(alice.isOpen()).thenReturn(true);
    when(alice.sendAsync(anyString())).thenReturn(CompletableFuture.completedFuture(null));
    registry.registerUserSession("alice", alice);
    registry.broadcastPayload(
        "p", Optional.of(Set.of("alice", "bob", "carol")), Optional.of(Set.of("dave")));

    verify(alice, times(1)).sendAsync("p");
  }

  @Test
  void cleanup_doesNotEvictOpenSessions() {
    ConnectionRegistry registry = new ConnectionRegistry();
    WebSocketSession alice = mock(WebSocketSession.class);
    when(alice.isOpen()).thenReturn(true);
    registry.registerUserSession("alice", alice);

    // Run cleanup twice
    registry.cleanupClosedSessions();
    registry.cleanupClosedSessions();

    assertEquals(1, registry.getRegisteredSessionCount());
    assertEquals(1, registry.getActiveSessionCount());
  }

  @Test
  void cleanup_doesNotEvictClosedSessionOnFirstCycle() {
    ConnectionRegistry registry = new ConnectionRegistry();
    WebSocketSession alice = mock(WebSocketSession.class);
    when(alice.isOpen()).thenReturn(false);
    when(alice.getId()).thenReturn("alice-session");
    registry.registerUserSession("alice", alice);

    // First cleanup - should record but not evict
    registry.cleanupClosedSessions();

    assertEquals(1, registry.getRegisteredSessionCount());
  }

  @Test
  void cleanup_evictsClosedSessionAfterTwoCycles() {
    ConnectionRegistry registry = new ConnectionRegistry();
    WebSocketSession alice = mock(WebSocketSession.class);
    when(alice.isOpen()).thenReturn(false);
    when(alice.getId()).thenReturn("alice-session");
    registry.registerUserSession("alice", alice);

    // First cleanup - records the closed session
    registry.cleanupClosedSessions();
    assertEquals(1, registry.getRegisteredSessionCount());

    // Second cleanup - should evict
    registry.cleanupClosedSessions();
    assertEquals(0, registry.getRegisteredSessionCount());
  }

  @Test
  void cleanup_doesNotEvictWhenUserReconnectsWithNewSession() {
    ConnectionRegistry registry = new ConnectionRegistry();

    // Alice connects with session1, which closes
    WebSocketSession session1 = mock(WebSocketSession.class);
    when(session1.isOpen()).thenReturn(false);
    when(session1.getId()).thenReturn("session-1");
    registry.registerUserSession("alice", session1);

    // First cleanup - records session1 as closed
    registry.cleanupClosedSessions();
    assertEquals(1, registry.getRegisteredSessionCount());

    // Alice reconnects with session2, which also closes before next cleanup
    WebSocketSession session2 = mock(WebSocketSession.class);
    when(session2.isOpen()).thenReturn(false);
    when(session2.getId()).thenReturn("session-2");
    registry.registerUserSession("alice", session2);

    // Second cleanup - should NOT evict because it's a different session
    registry.cleanupClosedSessions();
    assertEquals(1, registry.getRegisteredSessionCount());

    // Third cleanup - NOW it should evict session2
    registry.cleanupClosedSessions();
    assertEquals(0, registry.getRegisteredSessionCount());
  }

  @Test
  void cleanup_handlesOnCloseRemovingSessionBetweenCycles() {
    ConnectionRegistry registry = new ConnectionRegistry();
    WebSocketSession alice = mock(WebSocketSession.class);
    when(alice.isOpen()).thenReturn(false);
    when(alice.getId()).thenReturn("alice-session");
    registry.registerUserSession("alice", alice);

    // First cleanup - records the closed session
    registry.cleanupClosedSessions();
    assertEquals(1, registry.getRegisteredSessionCount());

    // Simulate @OnClose firing and removing the session
    registry.removeUserSession("alice", alice);
    assertEquals(0, registry.getRegisteredSessionCount());

    // Second cleanup - nothing to evict
    registry.cleanupClosedSessions();
    assertEquals(0, registry.getRegisteredSessionCount());
  }

  @Test
  void getActiveSessionCount_returnsOnlyOpenSessions() {
    ConnectionRegistry registry = new ConnectionRegistry();

    WebSocketSession open1 = mock(WebSocketSession.class);
    when(open1.isOpen()).thenReturn(true);
    WebSocketSession open2 = mock(WebSocketSession.class);
    when(open2.isOpen()).thenReturn(true);
    WebSocketSession closed = mock(WebSocketSession.class);
    when(closed.isOpen()).thenReturn(false);

    registry.registerUserSession("alice", open1);
    registry.registerUserSession("bob", open2);
    registry.registerUserSession("carol", closed);

    assertEquals(3, registry.getRegisteredSessionCount());
    assertEquals(2, registry.getActiveSessionCount());
  }
}
