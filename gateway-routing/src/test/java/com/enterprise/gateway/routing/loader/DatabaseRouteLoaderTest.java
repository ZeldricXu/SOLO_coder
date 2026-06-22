package com.enterprise.gateway.routing.loader;

import com.enterprise.gateway.admin.mapper.RouteMapper;
import com.enterprise.gateway.common.model.RouteDefinition;
import com.enterprise.gateway.routing.DynamicRouteService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DatabaseRouteLoaderTest {

    @Mock
    private DynamicRouteService dynamicRouteService;

    @Mock
    private RouteMapper routeMapper;

    private DatabaseRouteLoader databaseRouteLoader;

    @BeforeEach
    void setUp() {
        databaseRouteLoader = spy(new DatabaseRouteLoader(dynamicRouteService, routeMapper));
    }

    @Test
    void shouldLoadActiveRoutesOnStartup() {
        RouteDefinition activeRoute = RouteDefinition.builder()
                .id(1L)
                .routeId("route-active")
                .uri("http://localhost:8080")
                .status(1)
                .predicates("{\"name\":\"Path\",\"args\":{\"pattern\":\"/api/**\"}}")
                .build();

        doReturn(List.of(activeRoute)).when(databaseRouteLoader).queryAllRoutes();
        when(dynamicRouteService.convertToGatewayRoute(activeRoute))
                .thenReturn(new org.springframework.cloud.gateway.route.RouteDefinition());

        databaseRouteLoader.loadRoutesFromDatabase();

        verify(dynamicRouteService).convertToGatewayRoute(activeRoute);
        verify(dynamicRouteService).refreshRoutes(anyList());
    }

    @Test
    void shouldSkipInactiveRoutes() {
        RouteDefinition inactiveRoute = RouteDefinition.builder()
                .id(2L)
                .routeId("route-inactive")
                .uri("http://localhost:8081")
                .status(0)
                .build();

        doReturn(List.of(inactiveRoute)).when(databaseRouteLoader).queryAllRoutes();

        databaseRouteLoader.loadRoutesFromDatabase();

        verify(dynamicRouteService, never()).convertToGatewayRoute(any());
        verify(dynamicRouteService).refreshRoutes(Collections.emptyList());
    }

    @Test
    void shouldHandleEmptyDatabase() {
        doReturn(Collections.emptyList()).when(databaseRouteLoader).queryAllRoutes();

        assertThatCode(() -> databaseRouteLoader.loadRoutesFromDatabase())
                .doesNotThrowAnyException();

        verify(dynamicRouteService, never()).convertToGatewayRoute(any());
        verify(dynamicRouteService).refreshRoutes(Collections.emptyList());
    }

    @Test
    void shouldHandleDatabaseException() {
        doThrow(new RuntimeException("Database connection failed"))
                .when(databaseRouteLoader).queryAllRoutes();

        assertThatCode(() -> databaseRouteLoader.loadRoutesFromDatabase())
                .doesNotThrowAnyException();

        verify(dynamicRouteService, never()).convertToGatewayRoute(any());
        verify(dynamicRouteService, never()).refreshRoutes(anyList());
    }
}
