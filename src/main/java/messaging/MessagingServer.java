package messaging;

import io.micronaut.websocket.WebSocketBroadcaster;
import io.micronaut.websocket.WebSocketSession;
import io.micronaut.websocket.annotation.*;
import java.util.Map;

@ServerWebSocket("/ws/chat/{room}/{user}")
public class MessagingServer {

  private final WebSocketBroadcaster broadcaster;

  public MessagingServer(WebSocketBroadcaster broadcaster) {
    this.broadcaster = broadcaster;
  }

  @OnOpen
  public void onOpen(String room, String user, WebSocketSession session) {
    session.put("room", room);
    session.put("user", user);

    broadcast(room, Map.of("type", "system", "text", user + " joined", "room", room));
  }

  @OnMessage
  public void onMessage(String room, String user, String message) {
    broadcast(
        room,
        Map.of(
            "type", "chat",
            "from", user,
            "text", message,
            "room", room));
  }

  @OnClose
  public void onClose(String room, String user) {
    broadcast(room, Map.of("type", "system", "text", user + " left", "room", room));
  }

  private void broadcast(String room, Object payload) {
    broadcaster.broadcastSync(
        payload, session -> room.equals(session.get("room", String.class, null)));
  }
}
