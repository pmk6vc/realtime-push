package util.exception;

/** Thrown when a user attempts an action they are not authorized to perform. */
public class ForbiddenException extends RuntimeException {
  public ForbiddenException(String message) {
    super(message);
  }
}
