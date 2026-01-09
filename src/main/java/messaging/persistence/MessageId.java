package messaging.persistence;

import io.micronaut.data.annotation.Embeddable;
import io.micronaut.data.annotation.MappedProperty;
import java.util.UUID;

/** Composite primary key for Message entity (channel_id + message_id). */
@Embeddable
public record MessageId(
    @MappedProperty("channel_id") UUID channelId, @MappedProperty("message_id") UUID messageId) {}
