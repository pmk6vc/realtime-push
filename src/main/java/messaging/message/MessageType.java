package messaging.message;

import com.fasterxml.jackson.annotation.JsonValue;
import io.micronaut.serde.annotation.Serdeable;

/** Type of WebSocket message sent between client and server. */
@Serdeable
public enum MessageType {
  ACK("ack"),
  ERROR("error"),
  MESSAGE("message");

  private final String value;

  MessageType(String value) {
    this.value = value;
  }

  @JsonValue
  public String getValue() {
    return value;
  }
}
