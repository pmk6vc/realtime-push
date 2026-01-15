package e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import testutils.IntegrationInfraExtension;

/** E2E tests for channel membership management (owner actions). */
@Tag("e2e")
@ExtendWith(IntegrationInfraExtension.class)
public class MembershipManagementE2ETest {

  private static final MediaType JSON = MediaType.get("application/json");

  @Test
  void addMember_ownerCanAddMember(IntegrationInfraExtension.Infra infra) throws Exception {
    // Create a channel as alice
    String aliceToken = infra.passwordGrant("alice", "alice!");
    String bobId = infra.userSub("bob");

    RequestBody createBody = RequestBody.create("{\"channelName\":\"add-member-test\"}", JSON);
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

    // Add bob as a member
    RequestBody addBody = RequestBody.create("{\"userId\":\"" + bobId + "\"}", JSON);
    Request addRequest =
        new Request.Builder()
            .url(infra.envoyBaseUrl() + "/channels/" + channelId + "/members")
            .post(addBody)
            .header("Authorization", "Bearer " + aliceToken)
            .build();

    try (Response response = infra.http().newCall(addRequest).execute()) {
      String responseBody = response.body() != null ? response.body().string() : "";
      assertEquals(200, response.code(), "Owner should be able to add member: " + responseBody);
    }

    // Verify bob can now access the channel
    String bobToken = infra.passwordGrant("bob", "bob!");
    Request getRequest =
        new Request.Builder()
            .url(infra.envoyBaseUrl() + "/channels/" + channelId)
            .get()
            .header("Authorization", "Bearer " + bobToken)
            .build();

    try (Response response = infra.http().newCall(getRequest).execute()) {
      assertEquals(200, response.code(), "Bob should now have access to the channel");
    }
  }

  @Test
  void addMember_nonOwnerReceives403(IntegrationInfraExtension.Infra infra) throws Exception {
    // Use the pre-seeded test channel where alice is owner and bob is member
    String bobToken = infra.passwordGrant("bob", "bob!");
    String aliceId = infra.userSub("alice");
    String channelId = IntegrationInfraExtension.TEST_CHANNEL_ID;

    // Bob (non-owner) tries to add someone
    RequestBody addBody = RequestBody.create("{\"userId\":\"" + aliceId + "\"}", JSON);
    Request addRequest =
        new Request.Builder()
            .url(infra.envoyBaseUrl() + "/channels/" + channelId + "/members")
            .post(addBody)
            .header("Authorization", "Bearer " + bobToken)
            .build();

    try (Response response = infra.http().newCall(addRequest).execute()) {
      assertEquals(403, response.code(), "Non-owner should not be able to add members");
    }
  }

  @Test
  void addMember_idempotent(IntegrationInfraExtension.Infra infra) throws Exception {
    // Create a channel as alice
    String aliceToken = infra.passwordGrant("alice", "alice!");
    String bobId = infra.userSub("bob");

    RequestBody createBody = RequestBody.create("{\"channelName\":\"idempotent-test\"}", JSON);
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

    // Add bob twice - second request should succeed (idempotent)
    RequestBody addBody = RequestBody.create("{\"userId\":\"" + bobId + "\"}", JSON);
    for (int i = 0; i < 2; i++) {
      Request addRequest =
          new Request.Builder()
              .url(infra.envoyBaseUrl() + "/channels/" + channelId + "/members")
              .post(addBody)
              .header("Authorization", "Bearer " + aliceToken)
              .build();

      try (Response response = infra.http().newCall(addRequest).execute()) {
        assertEquals(
            200, response.code(), "Adding member should be idempotent (attempt " + i + ")");
      }
    }
  }

  @Test
  void removeMember_ownerCanRemoveMember(IntegrationInfraExtension.Infra infra) throws Exception {
    // Create a channel as alice and add bob
    String aliceToken = infra.passwordGrant("alice", "alice!");
    String bobId = infra.userSub("bob");

    RequestBody createBody = RequestBody.create("{\"channelName\":\"remove-member-test\"}", JSON);
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

    // Add bob
    RequestBody addBody = RequestBody.create("{\"userId\":\"" + bobId + "\"}", JSON);
    Request addRequest =
        new Request.Builder()
            .url(infra.envoyBaseUrl() + "/channels/" + channelId + "/members")
            .post(addBody)
            .header("Authorization", "Bearer " + aliceToken)
            .build();

    try (Response addResponse = infra.http().newCall(addRequest).execute()) {
      assertEquals(200, addResponse.code());
    }

    // Remove bob
    Request removeRequest =
        new Request.Builder()
            .url(infra.envoyBaseUrl() + "/channels/" + channelId + "/members/" + bobId)
            .delete()
            .header("Authorization", "Bearer " + aliceToken)
            .build();

    try (Response response = infra.http().newCall(removeRequest).execute()) {
      assertEquals(204, response.code(), "Owner should be able to remove member");
    }

    // Verify bob no longer has access
    String bobToken = infra.passwordGrant("bob", "bob!");
    Request getRequest =
        new Request.Builder()
            .url(infra.envoyBaseUrl() + "/channels/" + channelId)
            .get()
            .header("Authorization", "Bearer " + bobToken)
            .build();

    try (Response response = infra.http().newCall(getRequest).execute()) {
      assertEquals(403, response.code(), "Bob should no longer have access to the channel");
    }
  }

  @Test
  void removeMember_nonOwnerReceives403(IntegrationInfraExtension.Infra infra) throws Exception {
    // Use the pre-seeded test channel where alice is owner and bob is member
    String bobToken = infra.passwordGrant("bob", "bob!");
    String aliceId = infra.userSub("alice");
    String channelId = IntegrationInfraExtension.TEST_CHANNEL_ID;

    // Bob (non-owner) tries to remove alice
    Request removeRequest =
        new Request.Builder()
            .url(infra.envoyBaseUrl() + "/channels/" + channelId + "/members/" + aliceId)
            .delete()
            .header("Authorization", "Bearer " + bobToken)
            .build();

    try (Response response = infra.http().newCall(removeRequest).execute()) {
      assertEquals(403, response.code(), "Non-owner should not be able to remove members");
    }
  }

  @Test
  void removeMember_ownerCannotRemoveSelf(IntegrationInfraExtension.Infra infra) throws Exception {
    // Create a channel as alice
    String aliceToken = infra.passwordGrant("alice", "alice!");
    String aliceId = infra.userSub("alice");

    RequestBody createBody =
        RequestBody.create("{\"channelName\":\"owner-self-remove-test\"}", JSON);
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

    // Alice tries to remove herself
    Request removeRequest =
        new Request.Builder()
            .url(infra.envoyBaseUrl() + "/channels/" + channelId + "/members/" + aliceId)
            .delete()
            .header("Authorization", "Bearer " + aliceToken)
            .build();

    try (Response response = infra.http().newCall(removeRequest).execute()) {
      assertEquals(400, response.code(), "Owner should not be able to remove themselves");
      JsonNode json = infra.mapper().readTree(response.body().string());
      assertEquals(
          "Owner cannot be removed. Delete the channel instead.", json.get("error").asText());
    }
  }

  @Test
  void addMember_channelNotFoundReturns404(IntegrationInfraExtension.Infra infra) throws Exception {
    String aliceToken = infra.passwordGrant("alice", "alice!");
    String bobId = infra.userSub("bob");
    String nonExistentId = "00000000-0000-0000-0000-000000000999";

    RequestBody addBody = RequestBody.create("{\"userId\":\"" + bobId + "\"}", JSON);
    Request addRequest =
        new Request.Builder()
            .url(infra.envoyBaseUrl() + "/channels/" + nonExistentId + "/members")
            .post(addBody)
            .header("Authorization", "Bearer " + aliceToken)
            .build();

    try (Response response = infra.http().newCall(addRequest).execute()) {
      assertEquals(404, response.code(), "Should return 404 for non-existent channel");
    }
  }
}
