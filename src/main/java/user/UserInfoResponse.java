package user;

import io.micronaut.serde.annotation.Serdeable;

/** Response containing the authenticated user's information. */
@Serdeable
public record UserInfoResponse(String userId) {}
