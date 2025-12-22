package messaging;

import static org.junit.jupiter.api.Assertions.*;

import io.micronaut.context.ApplicationContext;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import util.HeaderUserIdExtractor;

@MicronautTest
class WiringSmokeTest {

  @Inject ApplicationContext ctx;

  @Test
  void beansArePresent() {
    assertTrue(ctx.containsBean(MessagingServer.class));
    assertTrue(ctx.containsBean(ConnectionRegistry.class));
    assertTrue(ctx.containsBean(HeaderUserIdExtractor.class));
  }
}
