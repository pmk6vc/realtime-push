package channel.persistence;

import io.micronaut.data.annotation.MappedProperty;
import io.micronaut.serde.annotation.Serdeable;
import java.time.Instant;
import java.util.UUID;

/** Projection for user's channels, combining channel details with membership info. */
@Serdeable
public record UserChannelProjection(
    @MappedProperty("channel_id") UUID channelId,
    @MappedProperty("channel_name") String channelName,
    @MappedProperty("owner_user_id") UUID ownerUserId,
    @MappedProperty("created_at") Instant createdAt,
    @MappedProperty("joined_at") Instant joinedAt) {}
