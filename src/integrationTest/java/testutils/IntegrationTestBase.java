package testutils;

import okhttp3.Request;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Class that exists to provide common utilities for integration test ergonomics. Separate from any
 * core functionality related to spinning up / tearing down infrastructure.
 *
 * <p>Extend this in all integration test classes.
 */
@ExtendWith(IntegrationInfraExtension.class)
public abstract class IntegrationTestBase {

  protected final IntegrationInfraExtension.Infra infra(ExtensionContext ctx) {
    return IntegrationInfraExtension.infra(ctx);
  }

  protected final Request.Builder envoyRequest(ExtensionContext ctx, String path) {
    return new Request.Builder().url(infra(ctx).envoyBaseUrl() + path);
  }

  protected final String token(ExtensionContext ctx, String username, String password)
      throws Exception {
    return infra(ctx).passwordGrant(username, password);
  }

  protected final String bearer(String token) {
    return "Bearer " + token;
  }

  protected final Request.Builder envoyAuthed(ExtensionContext ctx, String path, String token) {
    return envoyRequest(ctx, path).header("Authorization", bearer(token));
  }
}
