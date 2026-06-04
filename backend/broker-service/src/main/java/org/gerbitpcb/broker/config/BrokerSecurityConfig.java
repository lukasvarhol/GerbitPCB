package org.gerbitpcb.broker.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import java.util.List;

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
    public CorsConfigurationSource corsConfigurationSource() {
	CorsConfiguration config = new CorsConfiguration();
	config.setAllowedOrigins(List.of("http://localhost:5173"));
	config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
	config.setAllowedHeaders(List.of("*"));
	config.setAllowCredentials(true);
	UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
	source.registerCorsConfiguration("/**", config);
	return source;
    }
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
	    .cors(cors -> cors.configurationSource(corsConfigurationSource()))
	    .csrf(AbstractHttpConfigurer::disable)
	    .authorizeHttpRequests(auth -> auth
				   .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/components").permitAll()
				   .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/components/stock-update").permitAll()
				   // Allow anyone to CREATE an order (The frontend checkout)
				   .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/transactions").permitAll()
				   // Require authentication to VIEW orders (The Manager dashboard)
				   .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/transactions").authenticated()	       
				   // Lock down everything else just in case
				   .anyRequest().authenticated()
				   )
	    .oauth2Client(Customizer.withDefaults())
	    .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.jwkSetUri("https://gerbitpcb.eu.auth0.com/.well-known/jwks.json")));
	return http.build();
    }
}
