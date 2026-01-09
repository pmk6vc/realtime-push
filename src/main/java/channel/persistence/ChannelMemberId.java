package channel.persistence;

import io.micronaut.data.annotation.Embeddable;
import io.micronaut.data.annotation.MappedProperty;
import java.util.UUID;

/** Composite primary key for ChannelMember entity (channel_id + user_id). */
@Embeddable
public record ChannelMemberId(
    @MappedProperty("channel_id") UUID channelId, @MappedProperty("user_id") UUID userId) {}
