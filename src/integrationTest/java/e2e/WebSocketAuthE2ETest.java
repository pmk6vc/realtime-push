package e2e;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import okhttp3.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import testutils.IntegrationInfraExtension;

@ExtendWith(IntegrationInfraExtension.class)
class WebSocketAuthE2ETest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private URI envoyChatWsUri(URI envoyBaseUri) {
        String http = envoyBaseUri.toString();
        String ws =
                http.startsWith("https://")
                        ? "wss://" + http.substring("https://".length())
                        : http.startsWith("http://")
                        ? "ws://" + http.substring("http://".length())
                        : http;
        return URI.create(ws + "/chat");
    }

    private record WsResult(boolean opened, Response upgradeResponse, String firstTextMessage, Throwable failure) {}

    private WsResult openWsAndReadFirstMessage(URI wsUri, Map<String, String> headers)
            throws Exception {
        OkHttpClient client =
                new OkHttpClient.Builder()
                        .callTimeout(Duration.ofSeconds(15))
                        .build();

        Request.Builder rb = new Request.Builder().url(wsUri.toString());
        if (headers != null) headers.forEach(rb::addHeader);
        Request req = rb.build();

        CountDownLatch done = new CountDownLatch(1);

        final boolean[] opened = {false};
        final Response[] upgradeResp = {null};
        final String[] firstMsg = {null};
        final Throwable[] failure = {null};

        WebSocket ws =
                client.newWebSocket(
                        req,
                        new WebSocketListener() {
                            @Override
                            public void onOpen(WebSocket webSocket, Response response) {
                                opened[0] = true;
                                upgradeResp[0] = response;
                            }

                            @Override
                            public void onMessage(WebSocket webSocket, String text) {
                                if (firstMsg[0] == null) {
                                    firstMsg[0] = text;
                                    done.countDown();
                                }
                            }

                            @Override
                            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                                failure[0] = t;
                                upgradeResp[0] = response;
                                done.countDown();
                            }

                            @Override
                            public void onClosed(WebSocket webSocket, int code, String reason) {
                                done.countDown();
                            }
                        });

        try {
            assertTrue(done.await(5, TimeUnit.SECONDS), "Timed out waiting for WS ack/failure");
            return new WsResult(opened[0], upgradeResp[0], firstMsg[0], failure[0]);
        } finally {
            ws.cancel();
            client.dispatcher().executorService().shutdown();
        }
    }

    @Test
    void ws_validToken_ackUserIdMatchesSub(IntegrationInfraExtension.Infra infra) throws Exception {
        String token = infra.passwordGrant("alice", "alice!");
        String expectedSub = infra.userSub("alice");

        URI wsUri = envoyChatWsUri(infra.envoyBaseUri());

        WsResult res =
                openWsAndReadFirstMessage(
                        wsUri,
                        Map.of("Authorization", "Bearer " + token));

        assertTrue(res.opened, "WebSocket did not open. failure=" + res.failure);
        assertNotNull(res.firstTextMessage, "No ack message received. failure=" + res.failure);

        JsonNode ack = MAPPER.readTree(res.firstTextMessage);
        assertEquals("ack", ack.get("type").asText(), "Expected ack: " + res.firstTextMessage);
        assertEquals(expectedSub, ack.get("userId").asText(), "Wrong injected userId");
        assertNotNull(ack.get("sessionId"), "Ack missing sessionId");
    }

    @Test
    void ws_spoofedXUserId_isOverwrittenByEnvoy(IntegrationInfraExtension.Infra infra)
            throws Exception {
        String token = infra.passwordGrant("alice", "alice!");
        String expectedSub = infra.userSub("alice");

        URI wsUri = envoyChatWsUri(infra.envoyBaseUri());

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + token);
        headers.put("X-User-Id", "evil-spoof");

        WsResult res = openWsAndReadFirstMessage(wsUri, headers);

        assertTrue(res.opened, "WebSocket did not open. failure=" + res.failure);
        assertNotNull(res.firstTextMessage, "No ack message received. failure=" + res.failure);

        JsonNode ack = MAPPER.readTree(res.firstTextMessage);
        assertEquals("ack", ack.get("type").asText(), "Expected ack: " + res.firstTextMessage);

        String actualUserId = ack.get("userId").asText();
        assertEquals(expectedSub, actualUserId, "Envoy did not overwrite spoofed header");
        assertNotEquals("evil-spoof", actualUserId, "Spoofed header leaked through");
    }
}