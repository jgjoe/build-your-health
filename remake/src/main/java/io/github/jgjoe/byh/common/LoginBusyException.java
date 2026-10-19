package io.github.jgjoe.byh.common;

/** Login rejected because the concurrent login limit is reached (429, code {@code TOO_MANY_REQUESTS}). */
public class LoginBusyException extends RuntimeException {

    public LoginBusyException(String message) {
        super(message);
    }
}
