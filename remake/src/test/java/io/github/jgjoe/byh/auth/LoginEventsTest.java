package io.github.jgjoe.byh.auth;

import io.github.jgjoe.byh.auth.dto.LoginRequest;
import io.github.jgjoe.byh.common.LoginBusyException;
import io.github.jgjoe.byh.common.UnauthorizedException;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.file.Path;
import java.time.Duration;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LoginEventsTest {
    @TempDir Path temporary;

    @Test
    void recordsPermitWaitAndServiceForAdmittedAndRejectedWork() throws Exception {
        Path file = temporary.resolve("admission.jfr");
        try (Recording recording = new Recording()) {
            recording.enable(LoginAdmissionEvent.class).withThreshold(Duration.ZERO);
            recording.start();
            LoginGate gate = new LoginGate(1, 0);
            assertThat(gate.run(() -> {
                assertThatThrownBy(() -> gate.run(() -> "must not run")).isInstanceOf(LoginBusyException.class);
                return "completed";
            })).isEqualTo("completed");
            assertThat(gate.availablePermits()).isEqualTo(1);
            recording.stop();
            recording.dump(file);
        }
        var events = RecordingFile.readAllEvents(file).stream()
                .filter(event -> event.getEventType().getName().equals("byh.LoginAdmission")).toList();
        assertThat(events).hasSize(2);
        assertThat(events.stream().map(event -> event.getString("decision")).toList())
                .containsExactlyInAnyOrder("admitted", "rejected");
        for (var event : events) {
            assertThat(event.getLong("waitNanos")).isGreaterThanOrEqualTo(0);
            assertThat(event.getLong("serviceNanos")).isGreaterThanOrEqualTo(0);
            assertThat(event.getLong("waitBudgetMillis")).isZero();
        }
    }

    @Test
    void recordsFailedCredentialWorkWithoutChangingItsOutcome() throws Exception {
        Path file = temporary.resolve("work.jfr");
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        when(encoder.encode(anyString())).thenReturn("dummy");
        AuthController controller = new AuthController(mock(MemberMapper.class), encoder,
                new LoginGate(2, 0), mock(LoginAttempts.class));
        try (Recording recording = new Recording()) {
            recording.enable(LoginWorkEvent.class).withThreshold(Duration.ZERO);
            recording.start();
            assertThatThrownBy(() -> controller.login(new LoginRequest("nobody", "wrong"),
                    mock(HttpServletRequest.class))).isInstanceOf(UnauthorizedException.class);
            recording.stop();
            recording.dump(file);
        }
        var events = RecordingFile.readAllEvents(file).stream()
                .filter(event -> event.getEventType().getName().equals("byh.LoginWork")).toList();
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getString("outcome")).isEqualTo("invalid");
        for (String field : new String[] {"accountReadNanos", "lookupNanos", "bcryptNanos", "accountWriteNanos"}) {
            assertThat(events.get(0).getLong(field)).isGreaterThanOrEqualTo(0);
        }
        assertThat(events.get(0).getEventType().getFields().stream().map(field -> field.getName()).toList())
                .doesNotContain("id", "password", "passwordHash", "cookie");
    }
}
