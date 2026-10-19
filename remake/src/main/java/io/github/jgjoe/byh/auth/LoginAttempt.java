package io.github.jgjoe.byh.auth;

import java.time.OffsetDateTime;

/** Five failures in a fixed five-minute window; an expired lock starts a fresh window. */
public record LoginAttempt(int failureCount, OffsetDateTime windowStartedAt,
                           OffsetDateTime lockedUntil, OffsetDateTime expiresAt) {
    public static LoginAttempt firstFailureAt(OffsetDateTime now) {
        return new LoginAttempt(1, now, null, now.plusMinutes(5));
    }

    public boolean isLockedAt(OffsetDateTime now) {
        return lockedUntil != null && lockedUntil.isAfter(now);
    }

    public LoginAttempt failedAt(OffsetDateTime now) {
        // A request admitted before the lock may finish later: never extend the existing lock.
        if (isLockedAt(now)) {
            return this;
        }
        if (lockedUntil != null || !windowStartedAt.plusMinutes(5).isAfter(now)) {
            return firstFailureAt(now);
        }
        int failures = failureCount + 1;
        OffsetDateTime until = failures == 5 ? now.plusSeconds(60) : null;
        OffsetDateTime expiry = windowStartedAt.plusMinutes(5);
        if (until != null && until.isAfter(expiry)) {
            expiry = until;
        }
        return new LoginAttempt(failures, windowStartedAt, until, expiry);
    }
}
