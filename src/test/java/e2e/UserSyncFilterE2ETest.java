package e2e;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import testutils.IntegrationInfraExtension;
import testutils.infrastructure.TestDataManager;

/**
 * E2E tests for UserSyncFilter which automatically creates users in the database when they make
 * their first authenticated request.
 */
@Tag("e2e")
@ExtendWith(IntegrationInfraExtension.class)
public class UserSyncFilterE2ETest {

  @Test
  void newUser_automaticallyCreatedInDatabase(IntegrationInfraExtension.Infra infra)
      throws Exception {
    TestDataManager testData = infra.testDataManager();

    // Create a unique user only in Keycloak (not in DB)
    String username = "synctest-" + System.currentTimeMillis();
    String password = "test123!";
    String userId = testData.createKeycloakUserOnly(username, password);

    // Verify user does NOT exist in database yet
    assertFalse(
        testData.userExistsInDatabase(userId),
        "User should not exist in database before first request");

    // Get a token for the new user
    String token = testData.passwordGrant(username, password);

    // Make an authenticated request via Envoy (triggers UserSyncFilter)
    Request request =
        new Request.Builder()
            .url(infra.envoyBaseUrl() + "/")
            .get()
            .header("Authorization", "Bearer " + token)
            .build();

    try (Response response = infra.http().newCall(request).execute()) {
      // Request should succeed
      assertTrue(response.isSuccessful(), "Request should succeed: " + response.code());
    }

    // Verify user NOW exists in database
    assertTrue(
        testData.userExistsInDatabase(userId), "User should exist in database after first request");
  }

  @Test
  void existingUser_noErrorOnSubsequentRequests(IntegrationInfraExtension.Infra infra)
      throws Exception {
    TestDataManager testData = infra.testDataManager();

    // Alice already exists in DB (seeded at startup)
    String aliceId = infra.userSub("alice");
    assertTrue(testData.userExistsInDatabase(aliceId), "Alice should already exist in database");

    String token = infra.passwordGrant("alice", "alice!");

    // Make multiple requests - should all succeed without errors
    for (int i = 0; i < 3; i++) {
      Request request =
          new Request.Builder()
              .url(infra.envoyBaseUrl() + "/")
              .get()
              .header("Authorization", "Bearer " + token)
              .build();

      try (Response response = infra.http().newCall(request).execute()) {
        assertTrue(response.isSuccessful(), "Request " + i + " should succeed: " + response.code());
      }
    }

    // User should still exist (idempotent)
    assertTrue(testData.userExistsInDatabase(aliceId), "Alice should still exist in database");
  }

  @Test
  void unauthenticatedRequest_proceedsWithoutError(IntegrationInfraExtension.Infra infra)
      throws Exception {
    // Request without Authorization header should proceed (and get 401 from Envoy)
    Request request =
        new Request.Builder().url(infra.envoyBaseUrl() + "/").get().build();

    try (Response response = infra.http().newCall(request).execute()) {
      // Should get 401 from Envoy (no auth) - not a 500 error from the filter
      assertTrue(
          response.code() == 401 || response.code() == 403,
          "Unauthenticated request should get auth error, not 500: " + response.code());
    }
  }
}