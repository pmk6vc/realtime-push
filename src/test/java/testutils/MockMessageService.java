package testutils;

import io.micronaut.context.annotation.Replaces;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import messaging.MessageService;
import messaging.persistence.Message;
import messaging.persistence.MessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import util.exception.MessagePersistenceException;

/**
 * Test replacement for MessageService that bypasses @Transactional requirements.
 *
 * <p>In the test environment, no datasource is configured, so the real
 * MessageService's @Transactional annotation cannot be processed. This mock extends MessageService
 * and overrides the transactional method to bypass AOP interception.
 */
@Singleton
@Replaces(MessageService.class)
@Requires(env = "test")
public class MockMessageService extends MessageService {

  private static final Logger LOG = LoggerFactory.getLogger(MockMessageService.class);

  private final MessageRepository messageRepository;

  public MockMessageService(MessageRepository messageRepository) {
    super(messageRepository);
    this.messageRepository = messageRepository;
  }

  /**
   * Saves a message without transaction management.
   *
   * <p>This override bypasses the parent's @Transactional annotation, which requires a datasource
   * that isn't available in the test environment.
   */
  @Override
  public Message saveMessage(Message message) {
    try {
      Message saved = messageRepository.save(message);
      LOG.debug(
          "Mock persisted message {} to channel {} from user {}",
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
