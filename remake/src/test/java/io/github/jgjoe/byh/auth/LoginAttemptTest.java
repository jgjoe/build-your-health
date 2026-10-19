package io.github.jgjoe.byh.auth;

import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LoginAttemptTest {
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-20T00:00:00Z");

    @Test
    void fifthFailureLocksForSixtySecondsAndAdmittedLateFailuresDoNotExtendIt() {
        LoginAttempt state = LoginAttempt.firstFailureAt(NOW);
        for (int i = 1; i < 5; i++) {
            state = state.failedAt(NOW.plusSeconds(i));
        }
        assertThat(state.failureCount()).isEqualTo(5);
        assertThat(state.lockedUntil()).isEqualTo(NOW.plusSeconds(64));
        assertThat(state.isLockedAt(NOW.plusSeconds(63))).isTrue();
        assertThat(state.failedAt(NOW.plusSeconds(50))).isEqualTo(state);
        assertThat(state.isLockedAt(NOW.plusSeconds(64))).isFalse();
        LoginAttempt after = state.failedAt(NOW.plusSeconds(64));
        assertThat(after.failureCount()).isEqualTo(1);
        assertThat(after.lockedUntil()).isNull();
        assertThat(after.windowStartedAt()).isEqualTo(NOW.plusSeconds(64));
    }

    @Test
    void failureAtTheFiveMinuteBoundaryStartsANewWindow() {
        LoginAttempt state = LoginAttempt.firstFailureAt(NOW);
        for (int i = 0; i < 3; i++) {
            state = state.failedAt(NOW.plusSeconds(1));
        }
        assertThat(state.failedAt(NOW.plusMinutes(5)).failureCount()).isEqualTo(1);
        assertThat(state.failedAt(NOW.plusMinutes(5)).windowStartedAt()).isEqualTo(NOW.plusMinutes(5));
    }

    @Test
    void cleanupExpiryNeverPrecedesAnActiveLock() {
        LoginAttempt state = LoginAttempt.firstFailureAt(NOW);
        for (int i = 0; i < 3; i++) {
            state = state.failedAt(NOW);
        }
        state = state.failedAt(NOW.plusMinutes(4).plusSeconds(59));
        assertThat(state.lockedUntil()).isEqualTo(NOW.plusMinutes(5).plusSeconds(59));
        assertThat(state.expiresAt()).isEqualTo(state.lockedUntil());
    }

    @Test
    void keysKeepExistingBinaryIdentityWithoutUnboundedStorage() {
        assertThat(LoginAttempts.key("alice")).containsExactly(LoginAttempts.key("alice"));
        assertThat(LoginAttempts.key("Alice")).isNotEqualTo(LoginAttempts.key("alice"));
        assertThat(LoginAttempts.key("alice ")).isNotEqualTo(LoginAttempts.key("alice"));
        assertThat(LoginAttempts.key("회원")).hasSize(32);
        assertThat(LoginAttempts.key("x".repeat(1000))).hasSize(32);
    }
}
