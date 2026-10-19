package io.github.jgjoe.byh.auth;

import io.github.jgjoe.byh.common.LoginBusyException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Short database operations only: no password hashing or HTTP session work inside a transaction. */
@Service
public class LoginAttempts {
    private final LoginAttemptMapper mapper;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public LoginAttempts(LoginAttemptMapper mapper, PlatformTransactionManager transactionManager, Clock clock) {
        this.mapper = mapper;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    public void checkAllowed(String id) {
        OffsetDateTime until = mapper.findLockUntil(key(id));
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (until != null && until.isAfter(now)) {
            Duration remaining = Duration.between(now, until);
            long seconds = remaining.getSeconds() + (remaining.getNano() == 0 ? 0 : 1);
            throw new LoginBusyException(LoginGate.BUSY, seconds);
        }
    }

    public void failed(String id) {
        byte[] key = key(id);
        OffsetDateTime now = OffsetDateTime.now(clock);
        try {
            recordFailure(key, now);
        } catch (DuplicateKeyException concurrentFirstFailure) {
            // The other transaction inserted the previously absent account and committed.
            // Re-read it with FOR UPDATE once; do not hide unrelated/repeated failures.
            recordFailure(key, now);
        }
    }

    private void recordFailure(byte[] key, OffsetDateTime now) {
        transactions.executeWithoutResult(status -> {
            LoginAttempt previous = mapper.findForUpdate(key);
            if (previous == null) {
                mapper.insert(key, LoginAttempt.firstFailureAt(now));
            } else {
                mapper.update(key, previous.failedAt(now));
            }
        });
    }

    public void succeeded(String id) {
        mapper.delete(key(id)); // One atomic, auto-committed statement, before session creation.
    }

    @Scheduled(fixedDelayString = "${byh.login.cleanup-delay-ms:10000}",
            initialDelayString = "${byh.login.cleanup-delay-ms:10000}")
    public void cleanupExpired() {
        mapper.deleteExpired(OffsetDateTime.now(clock));
    }

    static byte[] key(String id) {
        // Preserve existing binary ID equality, including case/spaces; do not invent normalization.
        // A fixed-size key also allows nonexistent, over-length IDs without unbounded key storage.
        try {
            return MessageDigest.getInstance("SHA-256").digest(id.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("Java requires SHA-256", impossible);
        }
    }
}
