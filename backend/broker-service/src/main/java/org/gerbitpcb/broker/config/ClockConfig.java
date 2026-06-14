package org.gerbitpcb.broker.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Provides a single {@link Clock} bean so deadline/TTL logic is testable.
 * Production uses the system clock; tests can inject a fixed/offset clock to
 * fast-forward the 15-minute retry deadline without waiting in real time.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
