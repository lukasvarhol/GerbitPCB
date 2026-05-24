package org.gerbitpcb.broker.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class BrokerSecurityConfig {

    /**
     * Currently we implement the requirements for lvl 1 of the assigment, this means the following
     * 1) there is no authentication required to CREATE an order (The frontend checkout)
     * 2) there is authentication required to VIEW orders (The Manager dashboard)
     *
     * for lvl 2 of the assignment we would need the following upgrades:
     * 1) now everybody needs some form of authentication/authorization flow, no more permitAll ...
     */

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth
                        // Allow anyone to CREATE an order (The frontend checkout)
                        .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/transactions").permitAll()
                        // Require authentication to VIEW orders (The Manager dashboard)
                        .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/transactions").authenticated()
                        // Lock down everything else just in case
                        .anyRequest().authenticated()
                )
                .oauth2Client(Customizer.withDefaults());
        return http.build();
    }
}
