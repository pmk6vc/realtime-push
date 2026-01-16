package messaging;

import jakarta.inject.Singleton;
import jakarta.transaction.Transactional;
import messaging.persistence.Message;
import messaging.persistence.MessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import util.exception.MessagePersistenceException;

/**
 * Service layer for message persistence operations.
 *
 * <p>This service wraps repository calls with explicit transaction boundaries, preparing for the
 * outbox pattern where we'll need to write both the message and an outbox entry atomically.
 */
@Singleton
public class MessageService {

  private static final Logger LOG = LoggerFactory.getLogger(MessageService.class);

  private final MessageRepository messageRepository;

  public MessageService(MessageRepository messageRepository) {
    this.messageRepository = messageRepository;
  }

  /**
   * Persists a message to the database.
   *
   * <p>This method is transactional to ensure atomicity. When the outbox pattern is implemented,
   * the outbox entry will be written in the same transaction.
   *
   * @param message the message to persist
   * @return the persisted message
   * @throws MessagePersistenceException if persistence fails
   */
  @Transactional
  public Message saveMessage(Message message) {
    try {
      Message saved = messageRepository.save(message);

      // TODO: Write to outbox table here for Kafka fanout (Phase 1, item 3)
      // outboxRepository.save(OutboxEntry.fromMessage(saved));

      LOG.debug(
          "Persisted message {} to channel {} from user {}",
          saved.messageId(),
          saved.channelId(),
          saved.senderUserId());

      return saved;
    } catch (Exception e) {
      LOG.error(
          "Failed to persist message to channel {} with messageId {}",
          message.channelId(),
          message.messageId(),
          e);
      throw new MessagePersistenceException(
          "Failed to persist message", message.channelId(), message.messageId(), e);
    }
  }
}
