package e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.fasterxml.jackson.databind.JsonNode;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import testutils.IntegrationInfraExtension;

@Tag("e2e")
@ExtendWith(IntegrationInfraExtension.class)
public class ChannelCrudE2ETest {

  private static final MediaType JSON = MediaType.get("application/json");

  @Test
  void createChannel_returnsChannelWithOwner(IntegrationInfraExtension.Infra infra)
      throws Exception {
    String token = infra.passwordGrant("alice", "alice!");
    String aliceId = infra.userSub("alice");

    RequestBody body = RequestBody.create("{\"channelName\":\"my-channel\"}", JSON);
    Request request =
        new Request.Builder()
            .url(infra.envoyBaseUrl() + "/channels")
            .post(body)
            .header("Authorization", "Bearer " + token)
            .build();

    try (Response response = infra.http().newCall(request).execute()) {
      String responseBody = response.body() != null ? response.body().string() : "";
      assertEquals(201, response.code(), "Response: " + responseBody);
      JsonNode json = infra.mapper().readTree(responseBody);
      assertNotNull(json.get("channelId").asText());
      assertEquals("my-channel", json.get("channelName").asText());
      assertEquals(aliceId, json.get("ownerUserId").asText());
      assertNotNull(json.get("createdAt").asText());
    }
  }

  @Test
  void getChannel_memberCanView(IntegrationInfraExtension.Infra infra) throws Exception {
    // Use the pre-seeded test channel where alice is owner
    String token = infra.passwordGrant("alice", "alice!");
    String channelId = IntegrationInfraExtension.TEST_CHANNEL_ID;

    Request request =
        new Request.Builder()
            .url(infra.envoyBaseUrl() + "/channels/" + channelId)
            .get()
            .header("Authorization", "Bearer " + token)
            .build();

    try (Response response = infra.http().newCall(request).execute()) {
      assertEquals(200, response.code());
      JsonNode json = infra.mapper().readTree(response.body().string());
      assertEquals(channelId, json.get("channelId").asText());
      assertEquals("test-channel", json.get("channelName").asText());
    }
  }

  @Test
  void getChannel_nonMemberReceives403(IntegrationInfraExtension.Infra infra) throws Exception {
    // First create a channel as alice
    String aliceToken = infra.passwordGrant("alice", "alice!");
    RequestBody createBody = RequestBody.create("{\"channelName\":\"private-channel\"}", JSON);
    Request createRequest =
        new Request.Builder()
            .url(infra.envoyBaseUrl() + "/channels")
            .post(createBody)
            .header("Authorization", "Bearer " + aliceToken)
            .build();

    String channelId;
    try (Response createResponse = infra.http().newCall(createRequest).execute()) {
      JsonNode json = infra.mapper().readTree(createResponse.body().string());
      channelId = json.get("channelId").asText();
    }

    // Try to get it as bob (who is not a member of this new channel)
    String bobToken = infra.passwordGrant("bob", "bob!");
    Request getRequest =
        new Request.Builder()
            .url(infra.envoyBaseUrl() + "/channels/" + channelId)
            .get()
            .header("Authorization", "Bearer " + bobToken)
            .build();

    try (Response response = infra.http().newCall(getRequest).execute()) {
      assertEquals(403, response.code());
      JsonNode json = infra.mapper().readTree(response.body().string());
      assertEquals("You must be a member of this channel", json.get("error").asText());
    }
  }

  @Test
  void updateChannel_ownerCanUpdate(IntegrationInfraExtension.Infra infra) throws Exception {
    // Create a channel as alice
    String token = infra.passwordGrant("alice", "alice!");
    RequestBody createBody = RequestBody.create("{\"channelName\":\"original-name\"}", JSON);
    Request createRequest =
        new Request.Builder()
            .url(infra.envoyBaseUrl() + "/channels")
            .post(createBody)
            .header("Authorization", "Bearer " + token)
            .build();

    String channelId;
    try (Response createResponse = infra.http().newCall(createRequest).execute()) {
      JsonNode json = infra.mapper().readTree(createResponse.body().string());
      channelId = json.get("channelId").asText();
    }

    // Update it
    RequestBody updateBody = RequestBody.create("{\"channelName\":\"new-name\"}", JSON);
    Request updateRequest =
        new Request.Builder()
            .url(infra.envoyBaseUrl() + "/channels/" + channelId)
            .put(updateBody)
            .header("Authorization", "Bearer " + token)
            .build();

    try (Response response = infra.http().newCall(updateRequest).execute()) {
      assertEquals(200, response.code());
      JsonNode json = infra.mapper().readTree(response.body().string());
      assertEquals("new-name", json.get("channelName").asText());
    }
  }

  @Test
  void updateChannel_nonOwnerReceives403(IntegrationInfraExtension.Infra infra) throws Exception {
    // Bob tries to update the test channel owned by alice
    String bobToken = infra.passwordGrant("bob", "bob!");
    String channelId = IntegrationInfraExtension.TEST_CHANNEL_ID;

    RequestBody updateBody = RequestBody.create("{\"channelName\":\"hacked-name\"}", JSON);
    Request updateRequest =
        new Request.Builder()
            .url(infra.envoyBaseUrl() + "/channels/" + channelId)
            .put(updateBody)
            .header("Authorization", "Bearer " + bobToken)
            .build();

    try (Response response = infra.http().newCall(updateRequest).execute()) {
      assertEquals(403, response.code());
      JsonNode json = infra.mapper().readTree(response.body().string());
      assertEquals("Only the channel owner can perform this action", json.get("error").asText());
    }
  }

  @Test
  void deleteChannel_ownerCanDelete(IntegrationInfraExtension.Infra infra) throws Exception {
    // Create a channel as alice
    String token = infra.passwordGrant("alice", "alice!");
    RequestBody createBody = RequestBody.create("{\"channelName\":\"to-delete\"}", JSON);
    Request createRequest =
        new Request.Builder()
            .url(infra.envoyBaseUrl() + "/channels")
            .post(createBody)
            .header("Authorization", "Bearer " + token)
            .build();

    String channelId;
    try (Response createResponse = infra.http().newCall(createRequest).execute()) {
      JsonNode json = infra.mapper().readTree(createResponse.body().string());
      channelId = json.get("channelId").asText();
    }

    // Delete it
    Request deleteRequest =
        new Request.Builder()
            .url(infra.envoyBaseUrl() + "/channels/" + channelId)
            .delete()
            .header("Authorization", "Bearer " + token)
            .build();

    try (Response response = infra.http().newCall(deleteRequest).execute()) {
      assertEquals(204, response.code());
    }

    // Verify it's gone
    Request getRequest =
        new Request.Builder()
            .url(infra.envoyBaseUrl() + "/channels/" + channelId)
            .get()
            .header("Authorization", "Bearer " + token)
            .build();

    try (Response response = infra.http().newCall(getRequest).execute()) {
      assertEquals(404, response.code());
    }
  }

  @Test
  void deleteChannel_nonOwnerReceives403(IntegrationInfraExtension.Infra infra) throws Exception {
    // Bob tries to delete the test channel owned by alice
    String bobToken = infra.passwordGrant("bob", "bob!");
    String channelId = IntegrationInfraExtension.TEST_CHANNEL_ID;

    Request deleteRequest =
        new Request.Builder()
            .url(infra.envoyBaseUrl() + "/channels/" + channelId)
            .delete()
            .header("Authorization", "Bearer " + bobToken)
            .build();

    try (Response response = infra.http().newCall(deleteRequest).execute()) {
      assertEquals(403, response.code());
    }
  }

  @Test
  void getChannel_notFoundReturns404(IntegrationInfraExtension.Infra infra) throws Exception {
    String token = infra.passwordGrant("alice", "alice!");
    String nonExistentId = "00000000-0000-0000-0000-000000000999";

    Request request =
        new Request.Builder()
            .url(infra.envoyBaseUrl() + "/channels/" + nonExistentId)
            .get()
            .header("Authorization", "Bearer " + token)
            .build();

    try (Response response = infra.http().newCall(request).execute()) {
      assertEquals(404, response.code());
    }
  }
}
