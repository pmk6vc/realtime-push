package networking;

import static org.junit.jupiter.api.Assertions.assertEquals;

import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.Test;
import testutils.IntegrationInfraExtension;
import testutils.IntegrationTestBase;

public class AuthIT extends IntegrationTestBase {

  @Test
  void missingToken_is401(IntegrationInfraExtension.Infra infra) throws Exception {
    Request req = new Request.Builder().url(infra.envoyBaseUrl() + "/__test/whoami").get().build();

    try (Response r = infra.http().newCall(req).execute()) {
      assertEquals(401, r.code());
    }
  }

  @Test
  void validToken_is200(IntegrationInfraExtension.Infra infra) throws Exception {
    String token = infra.passwordGrant("alice", "alice!");
    Request req =
        new Request.Builder()
            .url(infra.envoyBaseUrl() + "/__test/whoami")
            .get()
            .header("Authorization", bearer(token))
            .build();

    try (Response r = infra.http().newCall(req).execute()) {
      String body = r.body() == null ? "" : r.body().string();
      if (r.code() != 200) {
        System.err.println("=== Envoy /clusters ===");
        System.err.println(infra.envoyClusters());
        System.err.println("=== Envoy logs ===");
        System.err.println(infra.envoyContainer().getLogs());

        throw new AssertionError(
            "expected 200 but got "
                + r.code()
                + "\nbody="
                + body
                + "\nserver="
                + r.header("server")
                + "\nx-envoy-upstream-service-time="
                + r.header("x-envoy-upstream-service-time")
                + "\nvia="
                + r.header("via")
                + "\nx-envoy-decorator-operation="
                + r.header("x-envoy-decorator-operation"));
      }
    }
  }
}
