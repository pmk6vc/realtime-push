package e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import com.fasterxml.jackson.databind.JsonNode;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import testutils.IntegrationInfraExtension;

import java.util.function.Consumer;

@ExtendWith(IntegrationInfraExtension.class)
public class AuthIntegrationTest {

  private record ResponseRecord(int code, String body, String contentType) {}

  private static ResponseRecord callHello(IntegrationInfraExtension.Infra infra, Consumer<Request.Builder> mut)
          throws Exception {
    Request.Builder b = new Request.Builder().url(infra.envoyBaseUrl() + "/").get();
    mut.accept(b);
    try (Response r = infra.http().newCall(b.build()).execute()) {
      return new ResponseRecord(r.code(), r.body() != null ? r.body().string() : "", r.header("Content-Type"));
    }
  }

  @Test
  void missingTokenYields401(IntegrationInfraExtension.Infra infra) throws Exception {
    ResponseRecord rb = callHello(infra, b -> {});
    assertEquals(401, rb.code);
  }

  @Test
  void malformedAuthorizationHeaderYields401(IntegrationInfraExtension.Infra infra) throws Exception {
    ResponseRecord rb = callHello(infra, b -> b.header("Authorization", "NotBearer abc.def.ghi"));
    assertEquals(401, rb.code);
  }

  @Test
  void malformedJwtYields401(IntegrationInfraExtension.Infra infra) throws Exception {
    ResponseRecord rb = callHello(infra, b -> b.header("Authorization", "Bearer definitely-not-a-jwt"));
    assertEquals(401, rb.code);
  }

  @Test
  void spoofedUserIdHeaderOverwrittenByEnvoy(IntegrationInfraExtension.Infra infra) throws Exception {
    String token = infra.passwordGrant("alice", "alice!");
    String expectedSub = infra.userSub("alice");
    ResponseRecord rb =
            callHello(
                    infra,
                    b -> {
                      b.header("Authorization", "Bearer " + token);
                      b.header("X-User-Id", "evil-spoofed-value");
                    });
    JsonNode jsonBody = infra.readJsonBody(rb.body);
    assertEquals(200, rb.code);
    assertEquals(expectedSub, jsonBody.get("userId").asText(), "Envoy did not overwrite spoofed X-User-Id");
  }

  @Test
  void validJwtYields200AndUserIdInjected(IntegrationInfraExtension.Infra infra)
      throws Exception {
    String token = infra.passwordGrant("alice", "alice!");
    String expectedSub = infra.userSub("alice");
    ResponseRecord rb = callHello(infra, b -> b.header("Authorization", "Bearer " + token));
    JsonNode jsonBody = infra.readJsonBody(rb.body);
    assertEquals(200, rb.code);
    assertEquals("hello", jsonBody.get("message").asText());
    assertEquals(expectedSub, jsonBody.get("userId").asText());
  }

  @Test
  void todo(IntegrationInfraExtension.Infra infra) throws Exception {
    // TODO add other tests suggested by chatgpt for envoy (invalid header, no spoofing, expired token, etc)
    // TODO add representative tests for websocket connections
    // TODO add tests for routing based on user ID in the hash ring if possible
  }
}
