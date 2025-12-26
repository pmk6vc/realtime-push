package testutils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micronaut.websocket.CloseReason;
import io.micronaut.websocket.WebSocketSession;

import java.time.Duration;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

public class AbstractWebSocketClientTemplate implements AutoCloseable {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final BlockingQueue<String> receivedMessages = new LinkedBlockingQueue<>();
    private final CompletableFuture<CloseReason> closeReasonFuture = new CompletableFuture<>();

    public void onText(String msg) { receivedMessages.add(msg); }
    public void onClose(CloseReason r) { closeReasonFuture.complete(r); }
    public void onError(Throwable t) { closeReasonFuture.completeExceptionally(t); }
    public BlockingQueue<String> getReceivedMessages() { return receivedMessages;}
    public CompletableFuture<CloseReason> getCloseReasonFuture() { return closeReasonFuture; }

    @Override
    public void close() {
        // No-op for subclass to implement if needed
    }
}