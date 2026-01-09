package channel.dto;

import io.micronaut.serde.annotation.Serdeable;

/** Request body for updating a channel. */
@Serdeable
public record UpdateChannelRequest(String channelName) {}
