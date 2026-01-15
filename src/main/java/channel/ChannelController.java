package channel;

import channel.dto.ChannelResponse;
import channel.dto.CreateChannelRequest;
import channel.dto.UpdateChannelRequest;
import channel.persistence.Channel;
import channel.service.ChannelService;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Delete;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.annotation.Put;
import java.util.UUID;
import util.HeaderUserIdExtractor;

/** REST controller for channel operations. */
@Controller("/channels")
public class ChannelController {

  private final ChannelService channelService;
  private final HeaderUserIdExtractor userIdExtractor;

  public ChannelController(ChannelService channelService, HeaderUserIdExtractor userIdExtractor) {
    this.channelService = channelService;
    this.userIdExtractor = userIdExtractor;
  }

  /** Creates a new channel. The authenticated user becomes the owner. */
  @Post
  @Produces(MediaType.APPLICATION_JSON)
  public HttpResponse<ChannelResponse> createChannel(
      @Body CreateChannelRequest request, HttpRequest<?> httpRequest) {
    UUID userId = extractUserId(httpRequest);
    Channel channel = channelService.createChannel(request.channelName(), userId);
    return HttpResponse.created(ChannelResponse.from(channel));
  }

  /** Gets a channel by ID. Only members can view. */
  @Get("/{channelId}")
  @Produces(MediaType.APPLICATION_JSON)
  public ChannelResponse getChannel(@PathVariable UUID channelId, HttpRequest<?> httpRequest) {
    UUID userId = extractUserId(httpRequest);
    Channel channel = channelService.getChannel(channelId, userId);
    return ChannelResponse.from(channel);
  }

  /** Updates a channel. Only the owner can update. */
  @Put("/{channelId}")
  @Produces(MediaType.APPLICATION_JSON)
  public ChannelResponse updateChannel(
      @PathVariable UUID channelId,
      @Body UpdateChannelRequest request,
      HttpRequest<?> httpRequest) {
    UUID userId = extractUserId(httpRequest);
    Channel channel = channelService.updateChannel(channelId, request.channelName(), userId);
    return ChannelResponse.from(channel);
  }

  /** Deletes a channel. Only the owner can delete. */
  @Delete("/{channelId}")
  public HttpResponse<?> deleteChannel(@PathVariable UUID channelId, HttpRequest<?> httpRequest) {
    UUID userId = extractUserId(httpRequest);
    channelService.deleteChannel(channelId, userId);
    return HttpResponse.noContent();
  }

  private UUID extractUserId(HttpRequest<?> httpRequest) {
    return userIdExtractor
        .extract(httpRequest)
        .map(UUID::fromString)
        .orElseThrow(() -> new IllegalStateException("User ID not found in request headers"));
  }
}
