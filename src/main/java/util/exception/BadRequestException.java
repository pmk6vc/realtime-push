package util.exception;

/** Thrown when a request is invalid. */
public class BadRequestException extends RuntimeException {
  public BadRequestException(String message) {
    super(message);
  }
}
