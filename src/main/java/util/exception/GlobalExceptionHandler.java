package util.exception;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Error;
import java.util.Map;

/** Global exception handler that converts exceptions to appropriate HTTP responses. */
@Controller
public class GlobalExceptionHandler {

  @Error(global = true, exception = ForbiddenException.class)
  public HttpResponse<Map<String, String>> handleForbidden(ForbiddenException ex) {
    return HttpResponse.status(HttpStatus.FORBIDDEN).body(Map.of("error", ex.getMessage()));
  }

  @Error(global = true, exception = NotFoundException.class)
  public HttpResponse<Map<String, String>> handleNotFound(NotFoundException ex) {
    return HttpResponse.status(HttpStatus.NOT_FOUND).body(Map.of("error", ex.getMessage()));
  }

  @Error(global = true, exception = BadRequestException.class)
  public HttpResponse<Map<String, String>> handleBadRequest(BadRequestException ex) {
    return HttpResponse.status(HttpStatus.BAD_REQUEST).body(Map.of("error", ex.getMessage()));
  }
}
