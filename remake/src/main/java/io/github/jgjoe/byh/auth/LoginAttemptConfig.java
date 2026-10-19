package io.github.jgjoe.byh.auth;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
public class LoginAttemptConfig {
    @Bean
    public Clock loginClock() {
        return Clock.systemUTC();
    }
}
