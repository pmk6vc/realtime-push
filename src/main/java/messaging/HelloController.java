package messaging;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Produces;
import java.util.Map;

@Controller("/")
public class HelloController {

  @Get
  @Produces(MediaType.APPLICATION_JSON)
  public Map<String, String> hello(HttpRequest<?> request) {
    return Map.of("message", "hello", "userId", request.getHeaders().get("x-user-id"));
  }
}
