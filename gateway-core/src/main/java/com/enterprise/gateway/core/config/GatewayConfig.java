package com.enterprise.gateway.core.config;

import org.springframework.cloud.gateway.route.InMemoryRouteDefinitionRepository;
import org.springframework.cloud.gateway.route.RouteDefinitionLocator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Flux;

@Configuration
public class GatewayConfig {

    @Bean
    public InMemoryRouteDefinitionRepository inMemoryRouteDefinitionRepository() {
        return new InMemoryRouteDefinitionRepository();
    }

    @Bean
    public RouteDefinitionLocator routeDefinitionLocator(InMemoryRouteDefinitionRepository repository) {
        return () -> Flux.from(repository.getRouteDefinitions());
    }
}
