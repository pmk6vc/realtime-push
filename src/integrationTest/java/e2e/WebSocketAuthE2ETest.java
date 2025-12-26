package e2e;

import static org.junit.jupiter.api.Assertions.*;
import static testutils.E2ETestWebSocketClient.connect;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import okhttp3.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import testutils.E2ETestWebSocketClient;
import testutils.IntegrationInfraExtension;

@ExtendWith(IntegrationInfraExtension.class)
class WebSocketAuthE2ETest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private URI envoyChatWsUri(URI envoyBaseUri) {
    String http = envoyBaseUri.toString();
    String ws =
        http.startsWith("https://")
            ? "wss://" + http.substring("https://".length())
            : http.startsWith("http://") ? "ws://" + http.substring("http://".length()) : http;
    return URI.create(ws + "/chat");
  }

  @Test
  void wsSpoofedUserIdHeaderOverwrittenByEnvoy(IntegrationInfraExtension.Infra infra)
      throws Exception {
    String token = infra.passwordGrant("alice", "alice!");
    String expectedSub = infra.userSub("alice");
    URI wsUri = envoyChatWsUri(infra.envoyBaseUri());

    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("Authorization", "Bearer " + token);
    headers.put("X-User-Id", "evil-spoof");

    try (E2ETestWebSocketClient ws = connect(wsUri, headers)) {
      JsonNode ack = ws.awaitAck();
      String actualUserId = ack.get("userId").asText();
      assertEquals(expectedSub, actualUserId, "Envoy did not overwrite spoofed header");
      assertNotEquals("evil-spoof", actualUserId, "Spoofed header leaked through");
    }
  }

  @Test
  void wsValidTokenConnectsAndInjectsUserId(IntegrationInfraExtension.Infra infra)
      throws Exception {
    String token = infra.passwordGrant("alice", "alice!");
    String expectedSub = infra.userSub("alice");
    URI wsUri = envoyChatWsUri(infra.envoyBaseUri());

    try (E2ETestWebSocketClient ws = connect(wsUri, Map.of("Authorization", "Bearer " + token))) {
      JsonNode ack = ws.awaitAck();
      assertEquals(expectedSub, ack.get("userId").asText(), "Wrong injected userId");
      assertNotNull(ack.get("sessionId"), "Ack missing sessionId");
    }
  }
}
