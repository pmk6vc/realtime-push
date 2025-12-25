package e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.fasterxml.jackson.databind.JsonNode;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.Test;
import testutils.IntegrationInfraExtension;
import testutils.IntegrationTestBase;

public class AuthIntegrationTest extends IntegrationTestBase {

  @Test
  void missingToken_is401(IntegrationInfraExtension.Infra infra) throws Exception {
    Request req = new Request.Builder().url(infra.envoyBaseUrl() + "/__test/whoami").get().build();

    try (Response r = infra.http().newCall(req).execute()) {
      assertEquals(401, r.code());
    }
  }

  @Test
  void validToken_is200_andUserIdIsInjected(IntegrationInfraExtension.Infra infra)
      throws Exception {
    String token = infra.passwordGrant("alice", "alice!");
    String expectedSub = infra.userSub("alice");
    Request req =
        new Request.Builder()
            .url(infra.envoyBaseUrl() + "/")
            .get()
            .header("Authorization", "Bearer " + token)
            .build();
    try (Response r = infra.http().newCall(req).execute()) {
      assertEquals(200, r.code());

      JsonNode json = infra.readJson(r);
      assertEquals("hello", json.get("message").asText());

      JsonNode userId = json.get("userId");
      assertNotNull(userId, "userId missing from response");
      assertEquals(expectedSub, userId.asText());
    }
  }
}
