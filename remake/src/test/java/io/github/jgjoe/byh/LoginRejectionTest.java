package io.github.jgjoe.byh;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import io.github.jgjoe.byh.auth.LoginGate;
import io.github.jgjoe.byh.support.IntegrationTestBase;
import io.github.jgjoe.byh.support.Responses;
import io.github.jgjoe.byh.support.StatementCounter;
import io.github.jgjoe.byh.support.TestFixtures;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;

/** Concurrent login limit: logins beyond it get 429 at once and leave no trace. */
class LoginRejectionTest extends IntegrationTestBase {

    @Autowired
    private LoginGate loginGate;

    @Value("${byh.login.max-concurrent:2}")
    private int maxConcurrent;

    @Test
    void loginBeyondTheLimitIs429WithoutDatabaseWorkOrSessionChange() throws Exception {
        insertMember("alice", "pw1234", "앨리스");
        CountDownLatch holding = new CountDownLatch(maxConcurrent);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(maxConcurrent);
        try {
            List<Future<Boolean>> holders = new ArrayList<>();
            for (int i = 0; i < maxConcurrent; i++) {
                holders.add(pool.submit(() -> loginGate.run(() -> {
                    holding.countDown();
                    try {
                        return release.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                })));
            }
            assertThat(holding.await(5, TimeUnit.SECONDS)).isTrue();

            MockHttpSession session = new MockHttpSession();
            String sessionIdBefore = session.getId();
            StatementCounter.reset();
            MvcResult rejected = mockMvc.perform(post("/api/auth/login")
                            .contentType(APPLICATION_JSON)
                            .content(TestFixtures.loginBody("alice", "pw1234").getBytes(StandardCharsets.UTF_8))
                            .session(session))
                    .andReturn();

            assertThat(rejected.getResponse().getStatus()).isEqualTo(429);
            assertThat(rejected.getResponse().getHeader("Retry-After")).isEqualTo("1");
            assertThat(Responses.code(rejected)).isEqualTo("TOO_MANY_REQUESTS");
            assertThat(StatementCounter.executedStatements()).as("no member lookup when rejected").isZero();
            assertThat(session.getId()).as("session id unchanged").isEqualTo(sessionIdBefore);
            assertThat(session.getAttributeNames().hasMoreElements()).as("no login state stored").isFalse();

            release.countDown();
            for (Future<Boolean> holder : holders) {
                assertThat(holder.get(5, TimeUnit.SECONDS)).isTrue();
            }
        } finally {
            pool.shutdownNow();
        }

        MvcResult admitted = mockMvc.perform(post("/api/auth/login")
                        .contentType(APPLICATION_JSON)
                        .content(TestFixtures.loginBody("alice", "pw1234").getBytes(StandardCharsets.UTF_8)))
                .andReturn();
        assertThat(admitted.getResponse().getStatus()).isEqualTo(200);
    }
}
