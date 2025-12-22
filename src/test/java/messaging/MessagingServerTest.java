package messaging;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.micronaut.http.HttpRequest;
import io.micronaut.websocket.CloseReason;
import io.micronaut.websocket.WebSocketSession;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import util.HeaderUserIdExtractor;

@ExtendWith(MockitoExtension.class)
class MessagingServerTest {

  @Mock ConnectionRegistry registry;
  @Mock HeaderUserIdExtractor extractor;
  @Mock WebSocketSession session;
  @Mock HttpRequest<?> request;

  @Captor ArgumentCaptor<CloseReason> closeReasonCaptor;
  @Captor ArgumentCaptor<String> payloadCaptor;
  @Captor ArgumentCaptor<Set<String>> exclusionCaptor;

  @InjectMocks MessagingServer server;

  private static final String ALICE = "alice";
  private static final String BOB = "bob";

  @Test
  void onSessionOpen_closesWhenNoUserIdPresent() {
    when(extractor.extract(request)).thenReturn(Optional.empty());
    when(session.getId()).thenReturn("sess-1");

    server.onSessionOpen(session, request);

    verify(session).close(closeReasonCaptor.capture());
    assertEquals(CloseReason.POLICY_VIOLATION.getCode(), closeReasonCaptor.getValue().getCode());
    verifyNoInteractions(registry);
  }

  @Test
  void onSessionOpen_registersWhenUserIdPresent() {
    when(extractor.extract(request)).thenReturn(Optional.of(ALICE));

    server.onSessionOpen(session, request);

    verify(session).put(eq("userId"), eq(ALICE));
    verify(registry).registerUserSession(eq(ALICE), eq(session));
  }

  @Test
  void onSessionClose_removesWhenUserKnown() {
    when(session.get(eq("userId"), eq(String.class), isNull())).thenReturn(ALICE);

    server.onSessionClose(session);

    verify(registry).removeUserSession(ALICE, session);
  }

  @Test
  void onSessionClose_noRemoveWhenUserUnknown() {
    when(session.get(eq("userId"), eq(String.class), isNull())).thenReturn(null);

    server.onSessionClose(session);

    verify(registry, never()).removeUserSession(anyString(), any());
  }

  @Test
  void onSessionMessage_buildsEscapedPayloadAndBroadcastsExcludingSender() {
    String raw = "Hello \"world\" \\ test";
    when(session.get(eq("userId"), eq(String.class), isNull())).thenReturn(ALICE);

    server.onSessionMessage(raw, session);

    verify(registry)
        .broadcastPayloadWithExclusions(payloadCaptor.capture(), exclusionCaptor.capture());

    // Confirm JSON escaping
    String payload = payloadCaptor.getValue();
    String expectedPayload =
        "{\"type\":\"message\",\"from\":\""
            + ALICE
            + "\",\"text\":\"Hello \\\"world\\\" \\\\ test\"}";
    assertEquals(payload, expectedPayload);

    // Confirm exclusion set
    Set<String> exclusions = exclusionCaptor.getValue();
    assertEquals(Set.of(ALICE), exclusions);
  }

  @Test
  void onSessionError_logsAndRemoves() {
    when(session.get(eq("userId"), eq(String.class), isNull())).thenReturn(ALICE);

    server.onSessionError(session, new RuntimeException("boom"));

    verify(registry).removeUserSession(ALICE, session);
  }
}
