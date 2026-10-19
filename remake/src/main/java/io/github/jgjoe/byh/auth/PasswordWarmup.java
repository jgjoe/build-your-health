package io.github.jgjoe.byh.auth;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Runs BCrypt checks on a throwaway password before the web server accepts requests, so the
 * first real logins do not pay for JIT compilation (docs/load-test-v2.md section 3). Runs during
 * context refresh, which completes before the embedded server starts listening.
 */
@Component
public class PasswordWarmup implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(PasswordWarmup.class);

    private final PasswordEncoder passwordEncoder;
    private final int iterations;

    public PasswordWarmup(PasswordEncoder passwordEncoder,
                          @Value("${byh.login.warmup-iterations:20}") int iterations) {
        if (iterations < 0) {
            throw new IllegalArgumentException("byh.login.warmup-iterations must not be negative");
        }
        this.passwordEncoder = passwordEncoder;
        this.iterations = iterations;
    }

    @Override
    public void afterSingletonsInstantiated() {
        if (iterations == 0) {
            return;
        }
        long started = System.nanoTime();
        String password = UUID.randomUUID().toString();
        String hash = passwordEncoder.encode(password);
        for (int i = 0; i < iterations; i++) {
            if (!passwordEncoder.matches(password, hash)) {
                throw new IllegalStateException("Password encoder failed its warmup check");
            }
        }
        log.info("Password warmup: {} checks in {} ms", iterations, (System.nanoTime() - started) / 1_000_000);
    }
}
