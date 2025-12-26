package e2e;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
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
  void ws_validToken_ackUserIdMatchesSub(IntegrationInfraExtension.Infra infra) throws Exception {
    String token = infra.passwordGrant("alice", "alice!");
    String expectedSub = infra.userSub("alice");
    URI wsUri = envoyChatWsUri(infra.envoyBaseUri());

    try (E2ETestWebSocketClient ws =
        E2ETestWebSocketClient.connect(wsUri, Map.of("Authorization", "Bearer " + token))) {
      JsonNode ack = ws.awaitAck();
      assertEquals(expectedSub, ack.get("userId").asText(), "Wrong injected userId");
      assertNotNull(ack.get("sessionId"), "Ack missing sessionId");
    }
  }
}
