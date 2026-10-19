package io.github.jgjoe.byh.auth;

import com.zaxxer.hikari.HikariDataSource;
import io.github.jgjoe.byh.support.IntegrationTestBase;
import io.github.jgjoe.byh.support.Responses;
import io.github.jgjoe.byh.support.StatementCounter;
import io.github.jgjoe.byh.support.TestFixtures;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@Import(LoginAttemptsIntegrationTest.TimeConfiguration.class)
class LoginAttemptsIntegrationTest extends IntegrationTestBase {
    @Autowired private LoginAttempts attempts;
    @Autowired private LoginAttemptMapper mapper;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private MutableClock clock;
    @Autowired private DataSource dataSource;
    @MockitoSpyBean private PasswordEncoder encoder;

    @BeforeEach
    void resetTime() {
        clock.now.set(Instant.parse("2026-10-20T00:00:00Z"));
    }

    @Test
    void existingAndMissingAccountsHaveTheSameThresholdResponseAndUnchangedSession() throws Exception {
        insertMember("alice", "pw1234", "앨리스");
        for (int i = 0; i < 5; i++) {
            assertThat(request("alice", "wrong", null).getResponse().getStatus()).isEqualTo(401);
            assertThat(request("nobody", "wrong", null).getResponse().getStatus()).isEqualTo(401);
        }
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("sentinel", "unchanged");
        String sessionId = session.getId();
        clearInvocations(encoder);
        StatementCounter.reset();
        MvcResult known = request("alice", "pw1234", session);
        MvcResult missing = request("nobody", "wrong", null);
        assertThat(known.getResponse().getStatus()).isEqualTo(429);
        assertThat(missing.getResponse().getStatus()).isEqualTo(429);
        assertThat(known.getResponse().getHeader("Retry-After")).isEqualTo("60");
        assertThat(missing.getResponse().getHeader("Retry-After")).isEqualTo("60");
        assertThat(known.getResponse().getContentAsString()).isEqualTo(missing.getResponse().getContentAsString());
        assertThat(Responses.code(known)).isEqualTo("TOO_MANY_REQUESTS");
        assertThat(StatementCounter.executedStatements()).isEqualTo(2); // Only account-state lookups.
        verify(encoder, never()).matches(any(), anyString());
        assertThat(session.getId()).isEqualTo(sessionId);
        assertThat(session.getAttribute("sentinel")).isEqualTo("unchanged");
        assertThat(java.util.Collections.list(session.getAttributeNames())).containsExactly("sentinel");
    }

    @Test
    void lockPersistsAcrossServiceRecreationExpiresAndSuccessResetsFailures() throws Exception {
        insertMember("alice", "pw1234", "앨리스");
        for (int i = 0; i < 5; i++) {
            attempts.failed("alice");
        }
        LoginAttempts recreated = new LoginAttempts(mapper, transactions, clock);
        assertThatThrownBy(() -> recreated.checkAllowed("alice"))
                .isInstanceOf(io.github.jgjoe.byh.common.LoginBusyException.class);
        clock.advance(Duration.ofMillis(59500));
        assertThat(request("alice", "pw1234", null).getResponse().getHeader("Retry-After")).isEqualTo("1");
        clock.advance(Duration.ofMillis(500));
        assertThat(request("alice", "wrong", null).getResponse().getStatus()).isEqualTo(401);
        assertThat(mapper.findForUpdate(LoginAttempts.key("alice")).failureCount()).isEqualTo(1);
        assertThat(request("alice", "pw1234", null).getResponse().getStatus()).isEqualTo(200);
        assertThat(mapper.findLockUntil(LoginAttempts.key("alice"))).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM LOGIN_ATTEMPT", Integer.class)).isZero();
        for (int i = 0; i < 4; i++) {
            assertThat(request("alice", "wrong", null).getResponse().getStatus()).isEqualTo(401);
        }
        assertThat(request("alice", "pw1234", null).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void failureWindowExpiresAndAccountsRemainIndependent() throws Exception {
        for (int i = 0; i < 4; i++) {
            attempts.failed("alice");
        }
        clock.advance(Duration.ofMinutes(5));
        attempts.failed("alice");
        assertThat(mapper.findForUpdate(LoginAttempts.key("alice")).failureCount()).isEqualTo(1);
        attempts.checkAllowed("Alice");
        attempts.checkAllowed("alice ");
        attempts.checkAllowed("unrelated");
        for (int i = 0; i < 4; i++) {
            attempts.failed("alice");
        }
        assertThatThrownBy(() -> attempts.checkAllowed("alice"))
                .isInstanceOf(io.github.jgjoe.byh.common.LoginBusyException.class);
        attempts.checkAllowed("Alice");
        attempts.checkAllowed("alice ");
    }

    @Test
    void simultaneousFirstFailuresAndIncrementsAreNotLost() throws Exception {
        var pool = Executors.newFixedThreadPool(5);
        CountDownLatch ready = new CountDownLatch(5);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < 5; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                    attempts.failed("concurrent");
                    return null;
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<?> future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        LoginAttempt state = mapper.findForUpdate(LoginAttempts.key("concurrent"));
        assertThat(state.failureCount()).isEqualTo(5);
        assertThat(state.lockedUntil()).isEqualTo(OffsetDateTime.now(clock).plusSeconds(60));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM LOGIN_ATTEMPT", Integer.class)).isEqualTo(1);
    }

    @Test
    void preAdmittedFailureDoesNotExtendLockAndSuccessfulCompletionResetsState() {
        for (int i = 0; i < 4; i++) {
            attempts.failed("alice");
        }
        attempts.checkAllowed("alice");
        attempts.checkAllowed("alice"); // Two requests can pass before either records its result.
        attempts.failed("alice");
        OffsetDateTime until = mapper.findLockUntil(LoginAttempts.key("alice"));
        clock.advance(Duration.ofSeconds(1));
        attempts.failed("alice");
        assertThat(mapper.findLockUntil(LoginAttempts.key("alice"))).isEqualTo(until);
        assertThat(mapper.findForUpdate(LoginAttempts.key("alice")).failureCount()).isEqualTo(5);
        attempts.succeeded("alice");
        assertThat(mapper.findForUpdate(LoginAttempts.key("alice"))).isNull();
        attempts.failed("alice");
        assertThat(mapper.findForUpdate(LoginAttempts.key("alice")).failureCount()).isEqualTo(1);
    }

    @Test
    void passwordVerificationHoldsNeitherTransactionNorDatabaseConnection() throws Exception {
        insertMember("alice", "pw1234", "앨리스");
        HikariDataSource pool = dataSource.unwrap(HikariDataSource.class);
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isZero();
            return invocation.callRealMethod();
        }).when(encoder).matches(any(), anyString());
        assertThat(request("alice", "wrong", null).getResponse().getStatus()).isEqualTo(401);
        assertThat(request("nobody", "wrong", null).getResponse().getStatus()).isEqualTo(401);
        assertThat(request("alice", "pw1234", null).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void expiredStateCleanupIsBoundedAndKeepsActiveState() {
        OffsetDateTime now = OffsetDateTime.now(clock);
        jdbc.update("""
                INSERT INTO LOGIN_ATTEMPT (ACCOUNT_KEY, FAILURE_COUNT, WINDOW_STARTED_AT, LOCKED_UNTIL, EXPIRES_AT)
                SELECT HEXTORAW(LPAD(TO_CHAR(LEVEL, 'FMXXXX'),64,'0')), 1, ?, NULL, ?
                FROM DUAL CONNECT BY LEVEL <= 1001
                """, now.minusMinutes(10), now.minusMinutes(5));
        attempts.failed("active");
        attempts.cleanupExpired();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM LOGIN_ATTEMPT", Integer.class)).isEqualTo(2);
        attempts.cleanupExpired();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM LOGIN_ATTEMPT", Integer.class)).isEqualTo(1);
        assertThat(mapper.findForUpdate(LoginAttempts.key("active"))).isNotNull();
    }

    private MvcResult request(String id, String password, MockHttpSession session) throws Exception {
        var request = post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(TestFixtures.loginBody(id, password).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        if (session != null) {
            request.session(session);
        }
        return mockMvc.perform(request).andReturn();
    }

    @TestConfiguration
    static class TimeConfiguration {
        @Bean
        @Primary
        MutableClock testLoginClock() {
            return new MutableClock();
        }
    }

    static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-20T00:00:00Z"));
        void advance(Duration duration) {
            now.updateAndGet(value -> value.plus(duration));
        }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(instant(), zone); }
        @Override public Instant instant() { return now.get(); }
    }
}
