package net.remixed.djsync.auth;

/** Turns a client's access token into a user id, or throws {@link InvalidTokenException}. */
public interface TokenVerifier {

    String verify(String token);

    class InvalidTokenException extends RuntimeException {
        public InvalidTokenException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
