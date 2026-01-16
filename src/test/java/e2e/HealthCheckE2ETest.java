package e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.SocketTimeoutException;
import java.time.Duration;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.containers.GenericContainer;
import testutils.IntegrationInfraExtension;

/** E2E tests for health check endpoint. */
@Tag("e2e")
@ExtendWith(IntegrationInfraExtension.class)
public class HealthCheckE2ETest {

  @Test
  void healthEndpoint_returnsOkWithDatabaseStatus(IntegrationInfraExtension.Infra infra)
      throws Exception {
    Request request = new Request.Builder().url(infra.envoyBaseUrl() + "/health").get().build();

    try (Response response = infra.http().newCall(request).execute()) {
      assertEquals(200, response.code(), "Health endpoint should return 200 when healthy");

      String body = response.body().string();
      JsonNode json = infra.mapper().readTree(body);

      assertEquals("UP", json.get("status").asText(), "Overall status should be UP");
      assertTrue(json.has("details"), "Response should include details");

      JsonNode details = json.get("details");
      assertTrue(details.has("jdbc"), "Details should include jdbc health indicator");
      assertEquals("UP", details.get("jdbc").get("status").asText(), "JDBC status should be UP");
    }
  }

  @Test
  void healthEndpoint_accessibleWithoutAuthentication(IntegrationInfraExtension.Infra infra)
      throws Exception {
    Request request = new Request.Builder().url(infra.envoyBaseUrl() + "/health").get().build();

    try (Response response = infra.http().newCall(request).execute()) {
      assertTrue(
          response.code() != 401, "Health endpoint should not require authentication, but got 401");
      assertEquals(200, response.code(), "Health endpoint should return 200");
    }
  }

  @Test
  void healthEndpoint_failsWhenDatabaseUnavailable(IntegrationInfraExtension.Infra infra)
      throws Exception {
    GenericContainer<?> citusMaster = infra.citusMasterContainer();
    Request request = new Request.Builder().url(infra.envoyBaseUrl() + "/health").get().build();

    // Use a short timeout client for the unhealthy check
    OkHttpClient shortTimeoutClient =
        infra
            .http()
            .newBuilder()
            .readTimeout(Duration.ofSeconds(5))
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    // Verify healthy before pausing
    try (Response response = infra.http().newCall(request).execute()) {
      assertEquals(200, response.code(), "Should be healthy before database pause");
    }

    try {
      // Pause the database container to simulate failure
      citusMaster.getDockerClient().pauseContainerCmd(citusMaster.getContainerId()).exec();

      // Give connection pool time to detect the failure
      Thread.sleep(2000);

      // Health check should now fail - either with timeout or 503
      // When DB is paused, JDBC health check blocks, so we expect timeout or non-200
      boolean healthCheckFailed = false;
      try (Response response = shortTimeoutClient.newCall(request).execute()) {
        // If we get a response, it should be 503
        healthCheckFailed = response.code() != 200;
      } catch (SocketTimeoutException e) {
        // Timeout is expected when DB is paused - health check is blocked
        healthCheckFailed = true;
      }

      assertTrue(
          healthCheckFailed,
          "Health endpoint should fail (timeout or non-200) when database is unavailable");
    } finally {
      // Always unpause to not affect other tests
      citusMaster.getDockerClient().unpauseContainerCmd(citusMaster.getContainerId()).exec();

      // Wait for database to recover
      Thread.sleep(3000);
    }

    // Verify recovery
    try (Response response = infra.http().newCall(request).execute()) {
      assertEquals(200, response.code(), "Should recover after database unpause");
    }
  }
}
