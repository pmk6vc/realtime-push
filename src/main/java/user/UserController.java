package user;

import channel.dto.ChannelMembershipResponse;
import channel.persistence.UserChannelProjection;
import channel.service.ChannelService;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Produces;
import java.util.List;
import java.util.UUID;
import util.HeaderUserIdExtractor;

/** REST controller for user operations. */
@Controller("/users")
public class UserController {

  private final ChannelService channelService;
  private final HeaderUserIdExtractor userIdExtractor;

  public UserController(ChannelService channelService, HeaderUserIdExtractor userIdExtractor) {
    this.channelService = channelService;
    this.userIdExtractor = userIdExtractor;
  }

  /** Lists all channels the authenticated user is a member of. */
  @Get("/me/channels")
  @Produces(MediaType.APPLICATION_JSON)
  public List<ChannelMembershipResponse> listMyChannels(HttpRequest<?> httpRequest) {
    UUID userId = extractUserId(httpRequest);
    return channelService.listUserChannels(userId).stream()
        .map(UserController::toResponse)
        .toList();
  }

  private static ChannelMembershipResponse toResponse(UserChannelProjection projection) {
    return new ChannelMembershipResponse(
        projection.channelId(),
        projection.channelName(),
        projection.ownerUserId(),
        projection.joinedAt());
  }

  private UUID extractUserId(HttpRequest<?> httpRequest) {
    return userIdExtractor
        .extract(httpRequest)
        .map(UUID::fromString)
        .orElseThrow(() -> new IllegalStateException("User ID not found in request headers"));
  }
}
