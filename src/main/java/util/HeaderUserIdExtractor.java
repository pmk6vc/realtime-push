package util;

import io.micronaut.http.HttpRequest;
import jakarta.inject.Singleton;
import java.util.Optional;

@Singleton
public class HeaderUserIdExtractor {

  public Optional<String> extract(HttpRequest<?> request) {
    // TODO: Extract from JWT instead of raw header
    return request
        .getHeaders()
        .get("X-User-Id", String.class)
        .map(String::trim)
        .filter(s -> !s.isEmpty());
  }
}
