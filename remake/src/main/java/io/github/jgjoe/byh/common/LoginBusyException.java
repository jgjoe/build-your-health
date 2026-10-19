package io.github.jgjoe.byh.common;

/** Generic login throttling response; does not expose account existence or the policy reason. */
public class LoginBusyException extends RuntimeException {
    private final long retryAfterSeconds;

    public LoginBusyException(String message) {
        this(message, 1);
    }

    public LoginBusyException(String message, long retryAfterSeconds) {
        super(message);
        if (retryAfterSeconds < 1) {
            throw new IllegalArgumentException("Retry-After must be positive");
        }
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
