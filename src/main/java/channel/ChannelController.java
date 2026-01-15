package channel;

import channel.dto.AddMemberRequest;
import channel.dto.ChannelResponse;
import channel.dto.CreateChannelRequest;
import channel.dto.TransferOwnershipRequest;
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

  /** Adds a member to a channel. Only the owner can add members. */
  @Post("/{channelId}/members")
  public HttpResponse<?> addMember(
      @PathVariable UUID channelId, @Body AddMemberRequest request, HttpRequest<?> httpRequest) {
    UUID userId = extractUserId(httpRequest);
    channelService.addMember(channelId, request.userId(), userId);
    return HttpResponse.ok();
  }

  /** Removes a member from a channel. Only the owner can remove members. */
  @Delete("/{channelId}/members/{memberUserId}")
  public HttpResponse<?> removeMember(
      @PathVariable UUID channelId, @PathVariable UUID memberUserId, HttpRequest<?> httpRequest) {
    UUID userId = extractUserId(httpRequest);
    channelService.removeMember(channelId, memberUserId, userId);
    return HttpResponse.noContent();
  }

  /** Transfers ownership of a channel to a new owner. Only the current owner can transfer. */
  @Post("/{channelId}/transfer-ownership")
  @Produces(MediaType.APPLICATION_JSON)
  public ChannelResponse transferOwnership(
      @PathVariable UUID channelId,
      @Body TransferOwnershipRequest request,
      HttpRequest<?> httpRequest) {
    UUID userId = extractUserId(httpRequest);
    Channel channel = channelService.transferOwnership(channelId, request.newOwnerUserId(), userId);
    return ChannelResponse.from(channel);
  }

  private UUID extractUserId(HttpRequest<?> httpRequest) {
    return userIdExtractor
        .extract(httpRequest)
        .map(UUID::fromString)
        .orElseThrow(() -> new IllegalStateException("User ID not found in request headers"));
  }
}
