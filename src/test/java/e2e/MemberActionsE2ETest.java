package e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import testutils.IntegrationInfraExtension;

/** E2E tests for member actions (list members, leave channel, list user's channels). */
@Tag("e2e")
@ExtendWith(IntegrationInfraExtension.class)
public class MemberActionsE2ETest {

  private static final MediaType JSON = MediaType.get("application/json");

  @Test
  void listMembers_memberCanViewMemberList(IntegrationInfraExtension.Infra infra) throws Exception {
    // Create a channel as alice and add bob
    String aliceToken = infra.passwordGrant("alice", "alice!");
    String aliceId = infra.userSub("alice");
    String bobId = infra.userSub("bob");

    RequestBody createBody = RequestBody.create("{\"channelName\":\"list-members-test\"}", JSON);
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

    // Add bob as member
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

    // List members
    Request listRequest =
        new Request.Builder()
            .url(infra.envoyBaseUrl() + "/channels/" + channelId + "/members")
            .get()
            .header("Authorization", "Bearer " + aliceToken)
            .build();

    try (Response response = infra.http().newCall(listRequest).execute()) {
      assertEquals(200, response.code());
      JsonNode json = infra.mapper().readTree(response.body().string());
      assertTrue(json.isArray(), "Response should be an array");
      assertEquals(2, json.size(), "Should have 2 members (alice + bob)");

      // Verify both alice and bob are in the list
      boolean hasAlice = false;
      boolean hasBob = false;
      for (JsonNode member : json) {
        String memberId = member.get("userId").asText();
        if (memberId.equals(aliceId)) hasAlice = true;
        if (memberId.equals(bobId)) hasBob = true;
      }
      assertTrue(hasAlice, "Alice should be in member list");
      assertTrue(hasBob, "Bob should be in member list");
    }
  }

  @Test
  void listMembers_nonMemberReceives403(IntegrationInfraExtension.Infra infra) throws Exception {
    // Create a channel as alice (bob is NOT a member)
    String aliceToken = infra.passwordGrant("alice", "alice!");
    String bobToken = infra.passwordGrant("bob", "bob!");

    RequestBody createBody =
        RequestBody.create("{\"channelName\":\"list-members-denied-test\"}", JSON);
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

    // Bob (non-member) tries to list members
    Request listRequest =
        new Request.Builder()
            .url(infra.envoyBaseUrl() + "/channels/" + channelId + "/members")
            .get()
            .header("Authorization", "Bearer " + bobToken)
            .build();

    try (Response response = infra.http().newCall(listRequest).execute()) {
      assertEquals(403, response.code(), "Non-member should not be able to list members");
    }
  }

  @Test
  void leaveChannel_memberCanLeave(IntegrationInfraExtension.Infra infra) throws Exception {
    // Create a channel as alice and add bob
    String aliceToken = infra.passwordGrant("alice", "alice!");
    String bobToken = infra.passwordGrant("bob", "bob!");
    String bobId = infra.userSub("bob");

    RequestBody createBody = RequestBody.create("{\"channelName\":\"leave-channel-test\"}", JSON);
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

    // Add bob as member
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

    // Bob leaves the channel
    Request leaveRequest =
        new Request.Builder()
            .url(infra.envoyBaseUrl() + "/channels/" + channelId + "/members/me")
            .delete()
            .header("Authorization", "Bearer " + bobToken)
            .build();

    try (Response response = infra.http().newCall(leaveRequest).execute()) {
      assertEquals(204, response.code(), "Member should be able to leave");
    }

    // Verify bob no longer has access
    Request getRequest =
        new Request.Builder()
            .url(infra.envoyBaseUrl() + "/channels/" + channelId)
            .get()
            .header("Authorization", "Bearer " + bobToken)
            .build();

    try (Response response = infra.http().newCall(getRequest).execute()) {
      assertEquals(403, response.code(), "Bob should no longer have access after leaving");
    }
  }

  @Test
  void leaveChannel_ownerCannotLeave(IntegrationInfraExtension.Infra infra) throws Exception {
    // Create a channel as alice
    String aliceToken = infra.passwordGrant("alice", "alice!");

    RequestBody createBody = RequestBody.create("{\"channelName\":\"owner-leave-test\"}", JSON);
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

    // Alice (owner) tries to leave
    Request leaveRequest =
        new Request.Builder()
            .url(infra.envoyBaseUrl() + "/channels/" + channelId + "/members/me")
            .delete()
            .header("Authorization", "Bearer " + aliceToken)
            .build();

    try (Response response = infra.http().newCall(leaveRequest).execute()) {
      assertEquals(400, response.code(), "Owner should not be able to leave");
      JsonNode json = infra.mapper().readTree(response.body().string());
      assertEquals(
          "Owner cannot leave. Transfer ownership or delete the channel instead.",
          json.get("error").asText());
    }
  }

  @Test
  void leaveChannel_nonMemberReceives403(IntegrationInfraExtension.Infra infra) throws Exception {
    // Create a channel as alice (bob is NOT a member)
    String aliceToken = infra.passwordGrant("alice", "alice!");
    String bobToken = infra.passwordGrant("bob", "bob!");

    RequestBody createBody = RequestBody.create("{\"channelName\":\"leave-nonmember-test\"}", JSON);
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

    // Bob (non-member) tries to leave
    Request leaveRequest =
        new Request.Builder()
            .url(infra.envoyBaseUrl() + "/channels/" + channelId + "/members/me")
            .delete()
            .header("Authorization", "Bearer " + bobToken)
            .build();

    try (Response response = infra.http().newCall(leaveRequest).execute()) {
      assertEquals(403, response.code(), "Non-member should get 403 when trying to leave");
    }
  }

  @Test
  void listMyChannels_returnsUserChannels(IntegrationInfraExtension.Infra infra) throws Exception {
    // Create two channels as alice, add bob to both
    String aliceToken = infra.passwordGrant("alice", "alice!");
    String bobToken = infra.passwordGrant("bob", "bob!");
    String bobId = infra.userSub("bob");

    // Create first channel
    RequestBody createBody1 = RequestBody.create("{\"channelName\":\"my-channels-test-1\"}", JSON);
    Request createRequest1 =
        new Request.Builder()
            .url(infra.envoyBaseUrl() + "/channels")
            .post(createBody1)
            .header("Authorization", "Bearer " + aliceToken)
            .build();

    String channelId1;
    try (Response createResponse = infra.http().newCall(createRequest1).execute()) {
      JsonNode json = infra.mapper().readTree(createResponse.body().string());
      channelId1 = json.get("channelId").asText();
    }

    // Create second channel
    RequestBody createBody2 = RequestBody.create("{\"channelName\":\"my-channels-test-2\"}", JSON);
    Request createRequest2 =
        new Request.Builder()
            .url(infra.envoyBaseUrl() + "/channels")
            .post(createBody2)
            .header("Authorization", "Bearer " + aliceToken)
            .build();

    String channelId2;
    try (Response createResponse = infra.http().newCall(createRequest2).execute()) {
      JsonNode json = infra.mapper().readTree(createResponse.body().string());
      channelId2 = json.get("channelId").asText();
    }

    // Add bob to both channels
    RequestBody addBody = RequestBody.create("{\"userId\":\"" + bobId + "\"}", JSON);
    for (String channelId : new String[] {channelId1, channelId2}) {
      Request addRequest =
          new Request.Builder()
              .url(infra.envoyBaseUrl() + "/channels/" + channelId + "/members")
              .post(addBody)
              .header("Authorization", "Bearer " + aliceToken)
              .build();

      try (Response addResponse = infra.http().newCall(addRequest).execute()) {
        assertEquals(200, addResponse.code());
      }
    }

    // Bob lists his channels
    Request listRequest =
        new Request.Builder()
            .url(infra.envoyBaseUrl() + "/users/me/channels")
            .get()
            .header("Authorization", "Bearer " + bobToken)
            .build();

    try (Response response = infra.http().newCall(listRequest).execute()) {
      assertEquals(200, response.code());
      JsonNode json = infra.mapper().readTree(response.body().string());
      assertTrue(json.isArray(), "Response should be an array");

      // Find our two test channels in the response
      int foundCount = 0;
      for (JsonNode channel : json) {
        String channelId = channel.get("channelId").asText();
        if (channelId.equals(channelId1) || channelId.equals(channelId2)) {
          foundCount++;
          // Verify structure
          assertTrue(channel.has("channelName"), "Should have channelName");
          assertTrue(channel.has("ownerUserId"), "Should have ownerUserId");
          assertTrue(channel.has("joinedAt"), "Should have joinedAt");
        }
      }
      assertEquals(2, foundCount, "Should find both test channels in bob's channel list");
    }
  }

  @Test
  void listMyChannels_emptyForNewUser(IntegrationInfraExtension.Infra infra) throws Exception {
    // Create a new user that's not a member of any channel
    String username = "lonely-" + System.currentTimeMillis();
    String password = "test123!";
    infra.testDataManager().seedUser(username, password);
    String token = infra.testDataManager().passwordGrant(username, password);

    // List channels
    Request listRequest =
        new Request.Builder()
            .url(infra.envoyBaseUrl() + "/users/me/channels")
            .get()
            .header("Authorization", "Bearer " + token)
            .build();

    try (Response response = infra.http().newCall(listRequest).execute()) {
      assertEquals(200, response.code());
      JsonNode json = infra.mapper().readTree(response.body().string());
      assertTrue(json.isArray(), "Response should be an array");
      assertEquals(0, json.size(), "New user should have no channels");
    }
  }
}
