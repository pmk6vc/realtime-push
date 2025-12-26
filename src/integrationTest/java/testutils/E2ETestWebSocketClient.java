package testutils;

import io.micronaut.websocket.CloseReason;
import okhttp3.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class E2ETestWebSocketClient extends AbstractWebSocketClientTemplate {
    private final WebSocket socket;
    private final CountDownLatch opened = new CountDownLatch(1);

    public E2ETestWebSocketClient(OkHttpClient client, Request req) {
        this.socket = client.newWebSocket(req, new WebSocketListener() {
            @Override public void onOpen(WebSocket webSocket, Response response) {
                opened.countDown();
            }
            @Override public void onMessage(WebSocket webSocket, String text) {
                onWebSocketMessage(text);
            }
            @Override public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                onWebSocketError(t);
            }
            @Override public void onClosed(WebSocket webSocket, int code, String reason) {
                onWebSocketClose(CloseReason.NORMAL);
            }
        });
    }

    public void awaitOpen(long timeoutMs) throws InterruptedException {
        if (!opened.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            throw new IllegalStateException("Timed out waiting for websocket open");
        }
    }

    public void sendText(String msg) { socket.send(msg); }

    @Override
    public void close() {
        socket.close(1000, "bye");
        socket.cancel();
    }
}