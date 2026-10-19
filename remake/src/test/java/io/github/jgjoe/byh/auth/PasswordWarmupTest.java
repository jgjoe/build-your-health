package io.github.jgjoe.byh.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

class PasswordWarmupTest {

    @Test
    void runsTheConfiguredNumberOfChecks() {
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        when(encoder.encode(anyString())).thenReturn("hash");
        when(encoder.matches(anyString(), anyString())).thenReturn(true);

        new PasswordWarmup(encoder, 3).afterSingletonsInstantiated();

        verify(encoder, times(1)).encode(anyString());
        verify(encoder, times(3)).matches(anyString(), anyString());
    }

    @Test
    void zeroIterationsDoesNothingAndNegativeIsRejected() {
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        new PasswordWarmup(encoder, 0).afterSingletonsInstantiated();
        verifyNoInteractions(encoder);
        assertThatThrownBy(() -> new PasswordWarmup(encoder, -1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void configuredEncoderKeepsBcryptCost10() {
        PasswordEncoder encoder = new PasswordConfig().passwordEncoder();
        String hash = encoder.encode("pw1234");
        assertThat(hash).startsWith("$2a$10$");
        assertThat(encoder.matches("pw1234", hash)).isTrue();
        assertThat(encoder.matches("wrong", hash)).isFalse();
    }
}
