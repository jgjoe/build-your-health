package io.github.jgjoe.byh.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jgjoe.byh.common.LoginBusyException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class LoginGateTest {

    @Test
    void rejectsBeyondLimitWithoutRunningTheLoginAndAdmitsAgainAfterRelease() throws Exception {
        LoginGate gate = new LoginGate(2, 0);
        CountDownLatch running = new CountDownLatch(2);
        CountDownLatch finish = new CountDownLatch(1);
        AtomicInteger concurrent = new AtomicInteger();
        AtomicInteger maxConcurrent = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<String>> holders = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                holders.add(pool.submit(() -> gate.run(() -> {
                    maxConcurrent.accumulateAndGet(concurrent.incrementAndGet(), Math::max);
                    running.countDown();
                    await(finish);
                    concurrent.decrementAndGet();
                    return "ok";
                })));
            }
            assertThat(running.await(5, TimeUnit.SECONDS)).isTrue();

            AtomicInteger rejectedCalls = new AtomicInteger();
            assertThatThrownBy(() -> gate.run(rejectedCalls::incrementAndGet)).isInstanceOf(LoginBusyException.class);
            assertThat(rejectedCalls).as("a rejected login must do no work").hasValue(0);

            finish.countDown();
            for (Future<String> holder : holders) {
                assertThat(holder.get(5, TimeUnit.SECONDS)).isEqualTo("ok");
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(maxConcurrent).hasValue(2);
        assertThat(gate.availablePermits()).isEqualTo(2);
        assertThat(gate.run(() -> "admitted")).isEqualTo("admitted");
    }

    @Test
    void waitsUpToTheLimitThenRejects() {
        LoginGate gate = new LoginGate(1, 50);
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            pool.submit(() -> gate.run(() -> {
                inside.countDown();
                await(finish);
                return "ok";
            }));
            await(inside);
            AtomicInteger rejectedCalls = new AtomicInteger();
            long started = System.nanoTime();
            assertThatThrownBy(() -> gate.run(rejectedCalls::incrementAndGet)).isInstanceOf(LoginBusyException.class);
            long waitedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertThat(waitedMs).as("waited for a permit before rejecting").isBetween(45L, 1000L);
            assertThat(rejectedCalls).hasValue(0);
        } finally {
            finish.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void admitsALoginWhoseWaitEndsWithARelease() throws Exception {
        LoginGate gate = new LoginGate(1, 5000);
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<String> holder = pool.submit(() -> gate.run(() -> {
                inside.countDown();
                await(finish);
                return "first";
            }));
            await(inside);
            Future<String> waiter = pool.submit(() -> gate.run(() -> "second"));
            Thread.sleep(100);
            assertThat(waiter).as("still waiting while the permit is held").isNotDone();
            finish.countDown();
            assertThat(holder.get(5, TimeUnit.SECONDS)).isEqualTo("first");
            assertThat(waiter.get(5, TimeUnit.SECONDS)).isEqualTo("second");
        } finally {
            pool.shutdownNow();
        }
        assertThat(gate.availablePermits()).isEqualTo(1);
    }

    @Test
    void interruptedWaitIsRejectedAndKeepsTheInterruptFlag() {
        LoginGate gate = new LoginGate(1, 5000);
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            pool.submit(() -> gate.run(() -> {
                inside.countDown();
                await(finish);
                return "ok";
            }));
            await(inside);
            Thread.currentThread().interrupt();
            assertThatThrownBy(() -> gate.run(() -> "never")).isInstanceOf(LoginBusyException.class);
            assertThat(Thread.interrupted()).as("interrupt flag restored (and cleared here)").isTrue();
        } finally {
            finish.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void releasesThePermitWhenTheLoginThrows() {
        LoginGate gate = new LoginGate(1, 0);
        assertThatThrownBy(() -> gate.run(() -> {
            throw new IllegalStateException("database down");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(gate.availablePermits()).isEqualTo(1);
        assertThatThrownBy(() -> gate.run(() -> {
            throw new LoginBusyException("nested");
        })).isInstanceOf(LoginBusyException.class);
        assertThat(gate.availablePermits()).as("never released more than acquired").isEqualTo(1);
    }

    @Test
    void rejectsInvalidSettings() {
        assertThatThrownBy(() -> new LoginGate(0, 50)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LoginGate(2, -1)).isInstanceOf(IllegalArgumentException.class);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
