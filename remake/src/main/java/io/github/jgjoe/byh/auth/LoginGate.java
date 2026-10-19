package io.github.jgjoe.byh.auth;

import io.github.jgjoe.byh.common.LoginBusyException;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Bounds how many logins run at once. A BCrypt check costs about 62 ms of CPU, so unbounded
 * logins under overload take the CPU from every other API. A login beyond the limit is rejected
 * immediately, before any database or BCrypt work (docs/load-test-v2.md section 3).
 */
@Component
public class LoginGate {

    private final Semaphore permits;

    public LoginGate(@Value("${byh.login.max-concurrent:2}") int maxConcurrent) {
        if (maxConcurrent < 1) {
            throw new IllegalArgumentException("byh.login.max-concurrent must be at least 1");
        }
        this.permits = new Semaphore(maxConcurrent);
    }

    public <T> T run(Supplier<T> login) {
        if (!permits.tryAcquire()) {
            throw new LoginBusyException("로그인 요청이 많습니다. 잠시 후 다시 시도해 주세요.");
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
