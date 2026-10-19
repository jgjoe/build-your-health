package io.github.jgjoe.byh.auth;

import io.github.jgjoe.byh.common.LoginBusyException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Bounds how many logins run at once. A BCrypt check costs about 62 ms of CPU, so unbounded
 * logins under overload take the CPU from every other API. A login waits at most
 * {@code byh.login.max-wait-ms} for a permit, absorbing momentary overlaps, and is then rejected
 * before any database or BCrypt work (docs/load-test-v2.md section 3).
 */
@Component
public class LoginGate {

    private static final String BUSY = "로그인 요청이 많습니다. 잠시 후 다시 시도해 주세요.";

    private final Semaphore permits;
    private final long maxWaitMs;

    public LoginGate(@Value("${byh.login.max-concurrent:2}") int maxConcurrent,
                     @Value("${byh.login.max-wait-ms:50}") long maxWaitMs) {
        if (maxConcurrent < 1) {
            throw new IllegalArgumentException("byh.login.max-concurrent must be at least 1");
        }
        if (maxWaitMs < 0) {
            throw new IllegalArgumentException("byh.login.max-wait-ms must not be negative");
        }
        this.permits = new Semaphore(maxConcurrent);
        this.maxWaitMs = maxWaitMs;
    }

    public <T> T run(Supplier<T> login) {
        try {
            if (!permits.tryAcquire(maxWaitMs, TimeUnit.MILLISECONDS)) {
                throw new LoginBusyException(BUSY);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LoginBusyException(BUSY);
        }
        try {
            return login.get();
        } finally {
            permits.release();
        }
    }

    int availablePermits() {
        return permits.availablePermits();
    }
}
