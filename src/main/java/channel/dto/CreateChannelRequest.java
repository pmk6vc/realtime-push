package channel.dto;

import io.micronaut.serde.annotation.Serdeable;

/** Request body for creating a new channel. */
@Serdeable
public record CreateChannelRequest(String channelName) {}
