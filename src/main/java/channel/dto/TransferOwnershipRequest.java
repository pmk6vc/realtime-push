package channel.dto;

import io.micronaut.serde.annotation.Serdeable;
import java.util.UUID;

/** Request to transfer channel ownership to a new owner. */
@Serdeable
public record TransferOwnershipRequest(UUID newOwnerUserId) {}
