package e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import testutils.E2ETestWebSocketClient;
import testutils.IntegrationInfraExtension;

/** E2E tests for channel membership enforcement on WebSocket messaging. */
@Tag("e2e")
@ExtendWith(IntegrationInfraExtension.class)
public class ChannelMembershipMessagingE2ETest {

  private static final MediaType JSON = MediaType.get("application/json");

  private URI envoyChatWsUri(URI envoyBaseUri) {
    String http = envoyBaseUri.toString();
    String ws =
        http.startsWith("https://")
            ? "wss://" + http.substring("https://".length())
            : http.startsWith("http://") ? "ws://" + http.substring("http://".length()) : http;
    return URI.create(ws + "/chat");
  }

  @Test
  void nonMember_cannotSendMessage(IntegrationInfraExtension.Infra infra) throws Exception {
    // Create a channel as alice (bob is NOT a member)
    String aliceToken = infra.passwordGrant("alice", "alice!");

    RequestBody createBody = RequestBody.create("{\"channelName\":\"no-bob-channel\"}", JSON);
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

    // Bob tries to send a message to the channel he's not a member of
    String bobToken = infra.passwordGrant("bob", "bob!");
    URI wsUri = envoyChatWsUri(infra.envoyBaseUri());

    try (E2ETestWebSocketClient bobWs =
        E2ETestWebSocketClient.connect(wsUri, Map.of("Authorization", "Bearer " + bobToken))) {
      bobWs.awaitAck();

      String message = String.format("{\"channelId\":\"%s\",\"text\":\"Hello!\"}", channelId);
      bobWs.sendMessage(message);

      // Should receive error response
      String response = bobWs.getReceivedMessages().poll(2, TimeUnit.SECONDS);
      assertNotNull(response, "Should receive error response");
      assertTrue(response.contains("\"type\":\"error\""), "Should be an error message");
      assertTrue(response.contains("not a member"), "Error should mention membership: " + response);
    }
  }

  @Test
  void member_canSendMessage(IntegrationInfraExtension.Infra infra) throws Exception {
    // Create a channel as alice and add bob
    String aliceToken = infra.passwordGrant("alice", "alice!");
    String bobId = infra.userSub("bob");

    RequestBody createBody = RequestBody.create("{\"channelName\":\"member-send-test\"}", JSON);
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

    // Bob (now a member) sends a message
    String bobToken = infra.passwordGrant("bob", "bob!");
    URI wsUri = envoyChatWsUri(infra.envoyBaseUri());

    try (E2ETestWebSocketClient bobWs =
        E2ETestWebSocketClient.connect(wsUri, Map.of("Authorization", "Bearer " + bobToken))) {
      bobWs.awaitAck();

      String message =
          String.format("{\"channelId\":\"%s\",\"text\":\"Hello from Bob!\"}", channelId);
      bobWs.sendMessage(message);

      // Should NOT receive an error (no response means message was accepted)
      String response = bobWs.getReceivedMessages().poll(500, TimeUnit.MILLISECONDS);
      if (response != null) {
        // If there's a response, it should not be an error
        assertTrue(
            !response.contains("\"type\":\"error\""),
            "Member should not receive error: " + response);
      }
    }
  }

  @Test
  void nonMember_doesNotReceiveBroadcast(IntegrationInfraExtension.Infra infra) throws Exception {
    // Create a channel as alice and add bob (charlie is NOT a member)
    String aliceToken = infra.passwordGrant("alice", "alice!");
    String bobId = infra.userSub("bob");

    RequestBody createBody = RequestBody.create("{\"channelName\":\"broadcast-test\"}", JSON);
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

    // Create charlie (not a member of this channel)
    String charlieUsername = "charlie-" + System.currentTimeMillis();
    String charliePassword = "charlie!";
    infra.testDataManager().seedUser(charlieUsername, charliePassword);
    String charlieToken = infra.testDataManager().passwordGrant(charlieUsername, charliePassword);

    // Connect alice, bob, and charlie to WebSocket
    String bobToken = infra.passwordGrant("bob", "bob!");
    URI wsUri = envoyChatWsUri(infra.envoyBaseUri());

    try (E2ETestWebSocketClient aliceWs =
            E2ETestWebSocketClient.connect(wsUri, Map.of("Authorization", "Bearer " + aliceToken));
        E2ETestWebSocketClient bobWs =
            E2ETestWebSocketClient.connect(wsUri, Map.of("Authorization", "Bearer " + bobToken));
        E2ETestWebSocketClient charlieWs =
            E2ETestWebSocketClient.connect(
                wsUri, Map.of("Authorization", "Bearer " + charlieToken))) {
      aliceWs.awaitAck();
      bobWs.awaitAck();
      charlieWs.awaitAck();

      // Alice sends a message to the channel
      String message =
          String.format("{\"channelId\":\"%s\",\"text\":\"Secret message!\"}", channelId);
      aliceWs.sendMessage(message);

      // Bob (member) should receive the message
      String bobReceived = bobWs.getReceivedMessages().poll(2, TimeUnit.SECONDS);
      assertNotNull(bobReceived, "Bob (member) should receive the message");
      assertTrue(bobReceived.contains("Secret message!"), "Bob should receive the message content");
      assertTrue(
          bobReceived.contains("\"type\":\"message\""), "Should be a message type: " + bobReceived);

      // Charlie (non-member) should NOT receive the message
      String charlieReceived = charlieWs.getReceivedMessages().poll(500, TimeUnit.MILLISECONDS);
      assertNull(charlieReceived, "Charlie (non-member) should NOT receive the message");

      // Alice (sender) should NOT receive her own message
      String aliceReceived = aliceWs.getReceivedMessages().poll(500, TimeUnit.MILLISECONDS);
      assertNull(aliceReceived, "Alice (sender) should NOT receive her own message");
    }
  }

  @Test
  void memberAfterLeaving_cannotSendMessage(IntegrationInfraExtension.Infra infra)
      throws Exception {
    // Create a channel as alice and add bob
    String aliceToken = infra.passwordGrant("alice", "alice!");
    String bobId = infra.userSub("bob");

    RequestBody createBody = RequestBody.create("{\"channelName\":\"leave-then-send-test\"}", JSON);
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
    String bobToken = infra.passwordGrant("bob", "bob!");
    Request leaveRequest =
        new Request.Builder()
            .url(infra.envoyBaseUrl() + "/channels/" + channelId + "/members/me")
            .delete()
            .header("Authorization", "Bearer " + bobToken)
            .build();

    try (Response leaveResponse = infra.http().newCall(leaveRequest).execute()) {
      assertEquals(204, leaveResponse.code());
    }

    // Bob tries to send a message after leaving
    URI wsUri = envoyChatWsUri(infra.envoyBaseUri());

    try (E2ETestWebSocketClient bobWs =
        E2ETestWebSocketClient.connect(wsUri, Map.of("Authorization", "Bearer " + bobToken))) {
      bobWs.awaitAck();

      String message =
          String.format("{\"channelId\":\"%s\",\"text\":\"I left but I'm back!\"}", channelId);
      bobWs.sendMessage(message);

      // Should receive error response
      String response = bobWs.getReceivedMessages().poll(2, TimeUnit.SECONDS);
      assertNotNull(response, "Should receive error response");
      assertTrue(response.contains("\"type\":\"error\""), "Should be an error message");
      assertTrue(response.contains("not a member"), "Error should mention membership");
    }
  }
}
