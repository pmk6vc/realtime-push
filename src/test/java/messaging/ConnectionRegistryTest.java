// java
package messaging;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.micronaut.websocket.CloseReason;
import io.micronaut.websocket.WebSocketSession;
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
}
