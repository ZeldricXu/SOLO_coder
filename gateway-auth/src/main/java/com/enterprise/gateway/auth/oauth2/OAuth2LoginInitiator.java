package com.enterprise.gateway.auth.oauth2;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.UUID;

@Slf4j
@Component
public class OAuth2LoginInitiator {

    private final ReactiveClientRegistrationRepository clientRegistrationRepository;

    @Value("${oauth2.default.registration-id:keycloak}")
    private String defaultRegistrationId;

    public OAuth2LoginInitiator(ReactiveClientRegistrationRepository clientRegistrationRepository) {
        this.clientRegistrationRepository = clientRegistrationRepository;
    }

    public Mono<Void> initiateLogin(ServerWebExchange exchange) {
        return clientRegistrationRepository.findByRegistrationId(defaultRegistrationId)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Default OAuth2 client registration not found: " + defaultRegistrationId)))
                .flatMap(clientRegistration -> {
                    OAuth2AuthorizationRequest authorizationRequest = buildAuthorizationRequest(clientRegistration, exchange.getRequest());
                    URI redirectUri = URI.create(authorizationRequest.getAuthorizationUri() + "?" + buildQueryString(authorizationRequest));
                    exchange.getResponse().setStatusCode(HttpStatus.FOUND);
                    exchange.getResponse().getHeaders().setLocation(redirectUri);
                    return exchange.getResponse().setComplete();
                })
                .onErrorResume(e -> {
                    log.error("Failed to initiate OAuth2 login", e);
                    exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
                    return exchange.getResponse().setComplete();
                });
    }

    private OAuth2AuthorizationRequest buildAuthorizationRequest(ClientRegistration clientRegistration, ServerHttpRequest request) {
        String redirectUri = request.getURI().getScheme() + "://" + request.getURI().getAuthority() + "/login/oauth2/code/" + clientRegistration.getRegistrationId();
        String state = UUID.randomUUID().toString();

        return OAuth2AuthorizationRequest.authorizationCode()
                .clientId(clientRegistration.getClientId())
                .authorizationUri(clientRegistration.getProviderDetails().getAuthorizationUri())
                .redirectUri(redirectUri)
                .scopes(clientRegistration.getScopes())
                .state(state)
                .build();
    }

    private String buildQueryString(OAuth2AuthorizationRequest authorizationRequest) {
        StringBuilder sb = new StringBuilder();
        sb.append("client_id=").append(authorizationRequest.getClientId());
        sb.append("&response_type=code");
        sb.append("&redirect_uri=").append(authorizationRequest.getRedirectUri());
        sb.append("&scope=").append(String.join(" ", authorizationRequest.getScopes()));
        sb.append("&state=").append(authorizationRequest.getState());
        return sb.toString();
    }
}
