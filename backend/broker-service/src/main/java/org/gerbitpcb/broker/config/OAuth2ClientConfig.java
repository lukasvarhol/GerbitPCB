package org.gerbitpcb.broker.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
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
 * Purpose: Configures a Machine-to-Machine (M2M) OAuth2 Client Credentials flow so the
 * Broker can call secured Supplier services.
 *
 * The Security Flow:
 * 1) RestTemplate initiates a request,
 * 2) the interceptor pauses it,
 * 3) the manager checks its token cache,
 * 4) fetches a token from Auth0 if missing,
 * 5) injects the Bearer token before sending.
 *
 * Key Components:
 * ClientRegistrationRepository reads client credentials from properties;
 * OAuth2AuthorizedClientService stores tokens in memory;
 * RestClientClientCredentialsTokenResponseClient executes the Auth0 token request.
 */
@Configuration
public class OAuth2ClientConfig {

    private static final Logger log = LoggerFactory.getLogger(OAuth2ClientConfig.class);

    @Value("${broker.security.oauth2.registration-id:broker}")
    private String registrationId;

    @Value("${broker.security.oauth2.audience:}")
    private String audience;

    /**
     * Overrides the default manager to inject the Auth0 audience parameter into the
     * client-credentials token request.
     */
    @Bean
//    @ConditionalOnBean(ClientRegistrationRepository.class)
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
