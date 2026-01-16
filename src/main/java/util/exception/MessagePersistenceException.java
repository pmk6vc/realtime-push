package util.exception;

import java.util.UUID;

/**
 * Thrown when a message fails to persist to the database. Includes context about the message
 * (channelId, messageId) to aid debugging.
 */
public class MessagePersistenceException extends RuntimeException {

  private final UUID channelId;
  private final UUID messageId;

  public MessagePersistenceException(
      String message, UUID channelId, UUID messageId, Throwable cause) {
    super(formatMessage(message, channelId, messageId), cause);
    this.channelId = channelId;
    this.messageId = messageId;
  }

  public UUID getChannelId() {
    return channelId;
  }

  public UUID getMessageId() {
    return messageId;
  }

  private static String formatMessage(String message, UUID channelId, UUID messageId) {
    return String.format("%s [channelId=%s, messageId=%s]", message, channelId, messageId);
  }
}
