package channel.dto;

import io.micronaut.serde.annotation.Serdeable;
import java.util.UUID;

/** Request to add a member to a channel. */
@Serdeable
public record AddMemberRequest(UUID userId) {}
