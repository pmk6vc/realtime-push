package e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

/** E2E tests for rate limiting on WebSocket messaging. */
@Tag("e2e")
@ExtendWith(IntegrationInfraExtension.class)
public class RateLimitE2ETest {

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
  void rateLimitExceeded_returnsError(IntegrationInfraExtension.Infra infra) throws Exception {
    // Create a channel as alice
    String aliceToken = infra.passwordGrant("alice", "alice!");

    RequestBody createBody = RequestBody.create("{\"channelName\":\"rate-limit-test\"}", JSON);
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

    // Connect alice and spam messages quickly
    URI wsUri = envoyChatWsUri(infra.envoyBaseUri());

    try (E2ETestWebSocketClient aliceWs =
        E2ETestWebSocketClient.connect(wsUri, Map.of("Authorization", "Bearer " + aliceToken))) {
      aliceWs.awaitAck();

      // Send messages as fast as possible to exceed rate limit (default: 10/sec)
      // Send 15 messages rapidly - some should fail
      int rateLimitErrors = 0;
      for (int i = 0; i < 15; i++) {
        String message =
            String.format("{\"channelId\":\"%s\",\"text\":\"Message %d\"}", channelId, i);
        aliceWs.sendMessage(message);
      }

      // Collect responses and count rate limit errors
      // Give a bit of time for all responses to arrive
      Thread.sleep(500);

      String response;
      while ((response = aliceWs.getReceivedMessages().poll(100, TimeUnit.MILLISECONDS)) != null) {
        if (response.contains("\"type\":\"error\"") && response.contains("Rate limit")) {
          rateLimitErrors++;
        }
      }

      // We should have received at least some rate limit errors
      assertTrue(
          rateLimitErrors > 0,
          "Should have received rate limit errors when sending 15 messages rapidly. "
              + "Got "
              + rateLimitErrors
              + " rate limit errors");
    }
  }

  @Test
  void differentUsers_haveIndependentRateLimits(IntegrationInfraExtension.Infra infra)
      throws Exception {
    // Create a channel and add bob
    String aliceToken = infra.passwordGrant("alice", "alice!");
    String bobId = infra.userSub("bob");

    RequestBody createBody =
        RequestBody.create("{\"channelName\":\"independent-rate-limits\"}", JSON);
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

    // Connect both alice and bob
    String bobToken = infra.passwordGrant("bob", "bob!");
    URI wsUri = envoyChatWsUri(infra.envoyBaseUri());

    try (E2ETestWebSocketClient aliceWs =
            E2ETestWebSocketClient.connect(wsUri, Map.of("Authorization", "Bearer " + aliceToken));
        E2ETestWebSocketClient bobWs =
            E2ETestWebSocketClient.connect(wsUri, Map.of("Authorization", "Bearer " + bobToken))) {
      aliceWs.awaitAck();
      bobWs.awaitAck();

      // Alice exhausts her rate limit (send 15 messages)
      for (int i = 0; i < 15; i++) {
        String message =
            String.format("{\"channelId\":\"%s\",\"text\":\"Alice msg %d\"}", channelId, i);
        aliceWs.sendMessage(message);
      }

      // Wait a bit for Alice's messages to be processed
      Thread.sleep(200);

      // Bob should still be able to send (his limit is independent)
      String bobMessage =
          String.format("{\"channelId\":\"%s\",\"text\":\"Bob's message\"}", channelId);
      bobWs.sendMessage(bobMessage);

      // Bob should NOT receive a rate limit error
      Thread.sleep(200);
      String bobResponse = bobWs.getReceivedMessages().poll(500, TimeUnit.MILLISECONDS);

      // Either no response (message accepted) or a broadcast message (not an error)
      if (bobResponse != null) {
        assertTrue(
            !bobResponse.contains("Rate limit"),
            "Bob should not be rate limited by Alice's activity: " + bobResponse);
      }

      // Alice should receive Bob's message (she's a member)
      // This confirms Bob's message was processed successfully
      boolean aliceReceivedBobMessage = false;
      String aliceResponse;
      while ((aliceResponse = aliceWs.getReceivedMessages().poll(500, TimeUnit.MILLISECONDS))
          != null) {
        if (aliceResponse.contains("Bob's message")) {
          aliceReceivedBobMessage = true;
          break;
        }
      }

      assertTrue(
          aliceReceivedBobMessage,
          "Alice should receive Bob's message, confirming Bob wasn't rate limited");
    }
  }
}
