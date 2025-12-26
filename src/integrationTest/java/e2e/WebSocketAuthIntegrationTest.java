package networking;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.websocket.WebSocketClient;
import jakarta.inject.Inject;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import reactor.core.publisher.Flux;
import testutils.IntegrationInfraExtension;
import testutils.TestWebSocketClient;

@ExtendWith(IntegrationInfraExtension.class)
class WebSocketAuthIntegrationTest {

    @Inject WebSocketClient wsClient;

    private URI envoyChatUri(IntegrationInfraExtension.Infra infra) {
        String http = infra.envoyBaseUrl();
        String ws =
                http.startsWith("https://")
                        ? "wss://" + http.substring("https://".length())
                        : http.startsWith("http://")
                        ? "ws://" + http.substring("http://".length())
                        : http;
        return URI.create(ws + "/chat");
    }

    @Test
    void webSocketConnectionRejectedOnMissingHeader(IntegrationInfraExtension.Infra infra) {
        URI uri = envoyChatUri(infra);
        assertThrows(Exception.class, () -> TestWebSocketClient.connect(wsClient, uri, null));
    }
}