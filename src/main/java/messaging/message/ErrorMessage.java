package messaging.message;

import io.micronaut.serde.annotation.Serdeable;

/** WebSocket error message sent to client when an operation fails. */
@Serdeable
public record ErrorMessage(MessageType type, String message) {
  public static ErrorMessage create(String message) {
    return new ErrorMessage(MessageType.ERROR, message);
  }
}
