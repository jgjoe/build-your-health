package io.github.jgjoe.byh.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

class PasswordConfigTest {

    @Test
    void configuredEncoderKeepsBcryptCost10() {
        PasswordEncoder encoder = new PasswordConfig().passwordEncoder();
        String hash = encoder.encode("pw1234");
        assertThat(hash).startsWith("$2a$10$");
        assertThat(encoder.matches("pw1234", hash)).isTrue();
        assertThat(encoder.matches("wrong", hash)).isFalse();
    }
}
