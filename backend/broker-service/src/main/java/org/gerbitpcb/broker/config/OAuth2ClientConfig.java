package org.gerbitpcb.broker.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProvider;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.endpoint.RestClientClientCredentialsTokenResponseClient;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.web.client.RestTemplate;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

/**
 * Configuration class for establishing a Machine-to-Machine (M2M) OAuth2 Client Credentials flow.
 * <p>
 * This configuration enables the Broker to authenticate itself when communicating with secured
 * downstream Supplier services. It provisions a globally available {@link RestTemplate} that
 * automatically fetches, caches, and attaches Auth0 Bearer tokens to outbound requests.
 * </p>
 * * <h3>The Security Flow:</h3>
 * <ol>
 * <li>The {@code RestTemplate} initiates an HTTP outbound request.</li>
 * <li>The registered {@link ClientHttpRequestInterceptor} pauses the request.</li>
 * <li>The {@link OAuth2AuthorizedClientManager} checks its in-memory cache for a valid token.</li>
 * <li>If no valid token is found, it reaches out to the Auth0 token endpoint to fetch a new one.</li>
 * <li>The interceptor injects the retrieved token into the {@code Authorization: Bearer} header and resumes the request.</li>
 * </ol>
 */
@Configuration
public class OAuth2ClientConfig {

    private static final Logger log = LoggerFactory.getLogger(OAuth2ClientConfig.class);

    @Value("${broker.security.oauth2.registration-id:broker}")
    private String registrationId;

    @Value("${broker.security.oauth2.audience:}")
    private String audience;

    /**
     * Configures the OAuth2 Client Manager responsible for handling the token lifecycle.
     * <p>
     * This customized bean overrides the default token response client to explicitly inject
     * an {@code audience} parameter into the Auth0 token request. Auth0 requires this parameter
     * to know which specific API (Resource Server) the Broker intends to access.
     * </p>
     *
     * @param clientRegistrationRepository the repository containing the Auth0 client credentials (mapped from application.properties)
     * @param authorizedClientService      the service responsible for caching the access tokens in memory
     * @return a customized {@link OAuth2AuthorizedClientManager} capable of requesting tokens for a specific audience
     */
    @Bean
    @ConditionalOnProperty(name = "broker.security.oauth2.client-enabled", havingValue = "true", matchIfMissing = true)
    public OAuth2AuthorizedClientManager authorizedClientManager(
            ClientRegistrationRepository clientRegistrationRepository,
            OAuth2AuthorizedClientService authorizedClientService) {
        RestClientClientCredentialsTokenResponseClient tokenResponseClient = new RestClientClientCredentialsTokenResponseClient();
        tokenResponseClient.addParametersConverter(grantRequest -> {
            MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
            if (audience != null && !audience.isBlank()) {
                params.add("audience", audience);
            }
            if (log.isDebugEnabled()) {
                log.debug("OAuth2 client-credentials audience requested: {}", audience);
            }
            return params;
        });

        OAuth2AuthorizedClientProvider authorizedClientProvider = OAuth2AuthorizedClientProviderBuilder.builder()
                .clientCredentials(clientCredentials -> clientCredentials.accessTokenResponseClient(tokenResponseClient))
                .build();

        AuthorizedClientServiceOAuth2AuthorizedClientManager manager =
                new AuthorizedClientServiceOAuth2AuthorizedClientManager(
                        clientRegistrationRepository,
                        authorizedClientService);
        manager.setAuthorizedClientProvider(authorizedClientProvider);
        return manager;
    }

    /**
     * Provisions a secure {@link RestTemplate} for making outbound HTTP calls to Suppliers.
     * <p>
     * This template is configured with strict connect and read timeouts (3 seconds) to prevent the Broker
     * from hanging indefinitely if a downstream Supplier is unresponsive. If the OAuth2 client manager
     * successfully initializes, an interceptor is attached to secure all outgoing requests.
     * </p>
     *
     * @param builder               the Spring-provided {@link RestTemplateBuilder}
     * @param clientManagerProvider an {@link ObjectProvider} containing the configured OAuth2 manager
     * @return a fully configured {@link RestTemplate}, secured with OAuth2 if the manager is available
     * @throws IllegalStateException if the interceptor attempts to fetch a token but Auth0 rejects the request
     */
    @Bean
    public RestTemplate restTemplate(RestTemplateBuilder builder,
                                     @Qualifier("authorizedClientManager") ObjectProvider<OAuth2AuthorizedClientManager> clientManagerProvider) {
        OAuth2AuthorizedClientManager clientManager = clientManagerProvider.getIfAvailable();
        RestTemplateBuilder configuredBuilder = builder
                .setConnectTimeout(Duration.ofSeconds(3))
                .setReadTimeout(Duration.ofSeconds(3));

        if (clientManager == null) {
            log.warn("OAuth2 client manager not available; outbound supplier calls will not include a Bearer token");
            return configuredBuilder.build();
        }

        ClientHttpRequestInterceptor interceptor = (request, body, execution) -> {
            OAuth2AuthorizeRequest authorizeRequest = OAuth2AuthorizeRequest
                    .withClientRegistrationId(registrationId)
                    .principal("broker-service")
                    .build();
            OAuth2AuthorizedClient client = clientManager.authorize(authorizeRequest);
            if (client == null || client.getAccessToken() == null) {
                throw new IllegalStateException("Failed to acquire OAuth2 access token");
            }
            request.getHeaders().setBearerAuth(client.getAccessToken().getTokenValue());
            if (log.isDebugEnabled()) {
                log.debug("OAuth2 Bearer token attached for registrationId={}", registrationId);
            }
            return execution.execute(request, body);
        };

        return configuredBuilder
                .additionalInterceptors(interceptor)
                .build();
    }
}
