package io.github.jgjoe.byh.auth;

import io.github.jgjoe.byh.auth.dto.LoginRequest;
import io.github.jgjoe.byh.common.InvalidParameterException;
import io.github.jgjoe.byh.common.LoginBusyException;
import io.github.jgjoe.byh.common.UnauthorizedException;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AuthControllerTest {
    private MemberMapper members;
    private PasswordEncoder encoder;
    private HttpServletRequest http;
    private AuthController controller;

    @BeforeEach
    void setup() {
        members = mock(MemberMapper.class);
        encoder = mock(PasswordEncoder.class);
        http = mock(HttpServletRequest.class);
        when(encoder.encode(anyString())).thenReturn("dummy-hash");
        controller = new AuthController(members, encoder, new LoginGate(2, 0));
    }

    @Test
    void knownUserWithWrongPasswordDoesExactlyOneStoredHashCheck() {
        Member member = new Member();
        member.setId("alice");
        member.setPasswordHash("stored-hash");
        when(members.findById("alice")).thenReturn(member);

        assertThatThrownBy(() -> controller.login(new LoginRequest("alice", "wrong"), http))
                .isInstanceOf(UnauthorizedException.class);
        verify(encoder).matches("wrong", "stored-hash");
        verify(encoder, times(1)).matches(anyString(), anyString());
        verifyNoInteractions(http);
    }

    @Test
    void missingUserStillFailsIfTheDummyPasswordMatches() {
        when(encoder.matches("wrong", "dummy-hash")).thenReturn(true);
        assertThatThrownBy(() -> controller.login(new LoginRequest("nobody", "wrong"), http))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("아이디 또는 비밀번호가 올바르지 않습니다.");
        verify(encoder).matches("wrong", "dummy-hash");
        verify(encoder, times(1)).matches(anyString(), anyString());
        verifyNoInteractions(http);
    }

    @Test
    void dummyHashIsNotRegeneratedForEachAttempt() {
        for (int attempt = 0; attempt < 2; attempt++) {
            assertThatThrownBy(() -> controller.login(new LoginRequest("nobody", "wrong"), http))
                    .isInstanceOf(UnauthorizedException.class);
        }
        verify(encoder, times(1)).encode(anyString());
        verify(encoder, times(2)).matches("wrong", "dummy-hash");
        verifyNoInteractions(http);
    }

    @Test
    void invalidInputDoesNotLookupOrVerifyCredentials() {
        assertThatThrownBy(() -> controller.login(new LoginRequest(" ", "wrong"), http))
                .isInstanceOf(InvalidParameterException.class);
        verifyNoInteractions(members, http);
        verify(encoder, never()).matches(anyString(), anyString());
    }

    @Test
    void globallyRejectedAttemptDoesNotLookupOrVerifyCredentials() {
        LoginGate gate = mock(LoginGate.class);
        when(gate.run(any())).thenThrow(new LoginBusyException("busy"));
        AuthController rejected = new AuthController(members, encoder, gate);
        assertThatThrownBy(() -> rejected.login(new LoginRequest("nobody", "wrong"), http))
                .isInstanceOf(LoginBusyException.class);
        verifyNoInteractions(members, http);
        verify(encoder, never()).matches(anyString(), anyString());
    }

    @Test
    void realDummyVerificationUsesACost10Hash() {
        PasswordEncoder real = spy(new PasswordConfig().passwordEncoder());
        AuthController actual = new AuthController(members, real, new LoginGate(2, 0));
        assertThatThrownBy(() -> actual.login(new LoginRequest("nobody", "wrong"), http))
                .isInstanceOf(UnauthorizedException.class);
        ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
        verify(real).matches(eq("wrong"), hash.capture());
        assertThat(hash.getValue()).startsWith("$2a$10$");
        verifyNoInteractions(http);
    }
}
