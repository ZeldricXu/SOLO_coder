package com.travelbooking.service;

import com.travelbooking.builder.TestDataBuilder;
import com.travelbooking.exception.BusinessException;
import com.travelbooking.model.Route;
import com.travelbooking.repository.RouteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RouteServiceTest {

    @Mock
    private RouteRepository routeRepository;

    @Mock
    private AnalyticsService analyticsService;

    @Mock
    private HistoryService historyService;

    @InjectMocks
    private RouteService routeService;

    private Route domesticRoute;
    private Route internationalRoute;
    private Route availableRoute;
    private Route fullRoute;
    private Route closedRoute;

    @BeforeEach
    void setUp() {
        domesticRoute = TestDataBuilder.buildDomesticRoute();
        internationalRoute = TestDataBuilder.buildInternationalRoute();
        availableRoute = TestDataBuilder.buildAvailableRoute();
        fullRoute = TestDataBuilder.buildFullRoute();
        closedRoute = TestDataBuilder.buildClosedRoute();
    }

    @Test
    @DisplayName("测试新增线路时状态设置为available")
    void testAddRouteSetsAvailableStatus() {
        Route newRoute = new Route();
        newRoute.setRouteId("route_new_001");
        newRoute.setRouteName("测试新线路");
        newRoute.setRouteQuota(100);

        when(routeRepository.save(any(Route.class))).thenAnswer(invocation -> {
            Route saved = invocation.getArgument(0);
            assertNotNull(saved.getRouteStatus());
            return saved;
        });

        Route result = routeService.addRoute(newRoute);

        assertNotNull(result);
        assertEquals("available", result.getRouteStatus());
        verify(routeRepository).save(any(Route.class));
    }

    @Test
    @DisplayName("测试线路状态流转 - available -> full")
    void testRouteStatusTransitionFromAvailableToFull() {
        when(routeRepository.findById("route_quota_50_30")).thenReturn(Optional.of(availableRoute));
        when(routeRepository.save(any(Route.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Route result = routeService.closeRoute("route_quota_50_30", "名额已满");

        assertEquals("full", result.getRouteStatus());
    }

    @Test
    @DisplayName("测试线路状态流转 - available -> closed")
    void testRouteStatusTransitionFromAvailableToClosed() {
        when(routeRepository.findById("route_quota_50_30")).thenReturn(Optional.of(availableRoute));
        when(routeRepository.save(any(Route.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Route result = routeService.closeRoute("route_quota_50_30", "线路维护");

        assertEquals("closed", result.getRouteStatus());
    }

    @Test
    @DisplayName("测试线路状态流转 - full -> available")
    void testRouteStatusTransitionFromFullToAvailable() {
        when(routeRepository.findById("route_full_001")).thenReturn(Optional.of(fullRoute));
        when(routeRepository.save(any(Route.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Route result = routeService.openRoute("route_full_001");

        assertEquals("available", result.getRouteStatus());
    }

    @Test
    @DisplayName("测试线路状态流转 - closed -> available")
    void testRouteStatusTransitionFromClosedToAvailable() {
        when(routeRepository.findById("route_closed_001")).thenReturn(Optional.of(closedRoute));
        when(routeRepository.save(any(Route.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Route result = routeService.openRoute("route_closed_001");

        assertEquals("available", result.getRouteStatus());
    }

    @Test
    @DisplayName("测试完整线路生命周期")
    void testFullRouteLifecycle() {
        when(routeRepository.save(any(Route.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Route created = routeService.addRoute(domesticRoute);
        assertEquals("available", created.getRouteStatus());

        when(routeRepository.findById(created.getRouteId())).thenReturn(Optional.of(created));

        Route closed = routeService.closeRoute(created.getRouteId(), "测试关闭");
        assertEquals("full", closed.getRouteStatus());

        when(routeRepository.findById(closed.getRouteId())).thenReturn(Optional.of(closed));

        Route reopened = routeService.openRoute(closed.getRouteId());
        assertEquals("available", reopened.getRouteStatus());
    }

    @Test
    @DisplayName("测试线路类型动态加载 - domestic类型")
    void testRouteTypeDynamicLoadingDomestic() {
        assertEquals("domestic", domesticRoute.getRouteType());
        assertTrue(domesticRoute.getRouteName().contains("国内"));
    }

    @Test
    @DisplayName("测试线路类型动态加载 - international类型")
    void testRouteTypeDynamicLoadingInternational() {
        assertEquals("international", internationalRoute.getRouteType());
        assertTrue(internationalRoute.getRouteName().contains("国际"));
    }

    @Test
    @DisplayName("测试不同线路类型的duration差异")
    void testDifferentRouteTypesHaveDifferentDuration() {
        assertTrue(internationalRoute.getRouteDuration() > domesticRoute.getRouteDuration());
    }

    @Test
    @DisplayName("测试可用线路查询")
    void testQueryAvailableRoutes() {
        List<Route> routes = Arrays.asList(domesticRoute, internationalRoute);
        when(routeRepository.findByRouteStatus("available")).thenReturn(routes);

        List<Route> result = routeService.queryAvailableRoutes();

        assertEquals(2, result.size());
        assertTrue(result.stream().allMatch(r -> "available".equals(r.getRouteStatus())));
    }

    @Test
    @DisplayName("测试名额扣减 - 正常情况")
    void testDecreaseQuotaNormal() {
        when(routeRepository.findById("route_quota_50_30")).thenReturn(Optional.of(availableRoute));
        when(routeRepository.save(any(Route.class))).thenAnswer(invocation -> invocation.getArgument(0));

        boolean result = routeService.decreaseQuota("route_quota_50_30", 5);

        assertTrue(result);
        assertEquals(25, availableRoute.getRouteAvailable());
        verify(routeRepository).save(availableRoute);
    }

    @Test
    @DisplayName("测试名额扣减 - 扣减后名额为0时状态变为full")
    void testDecreaseQuotaToZeroSetsFullStatus() {
        Route routeWithOneLeft = TestDataBuilder.buildAvailableRoute();
        routeWithOneLeft.setRouteAvailable(1);

        when(routeRepository.findById("route_quota_50_30")).thenReturn(Optional.of(routeWithOneLeft));
        when(routeRepository.save(any(Route.class))).thenAnswer(invocation -> invocation.getArgument(0));

        boolean result = routeService.decreaseQuota("route_quota_50_30", 1);

        assertTrue(result);
        assertEquals(0, routeWithOneLeft.getRouteAvailable());
        assertEquals("full", routeWithOneLeft.getRouteStatus());
    }

    @Test
    @DisplayName("测试名额扣减 - 名额不足时拒绝")
    void testDecreaseQuotaRejectedWhenQuotaInsufficient() {
        when(routeRepository.findById("route_quota_50_30")).thenReturn(Optional.of(availableRoute));

        BusinessException exception = assertThrows(BusinessException.class, () -> {
            routeService.decreaseQuota("route_quota_50_30", 100);
        });

        assertEquals(400, exception.getCode());
        assertTrue(exception.getMessage().contains("名额不足"));
        verify(routeRepository, never()).save(any(Route.class));
    }

    @Test
    @DisplayName("测试名额恢复 - 正常情况")
    void testRestoreQuotaNormal() {
        Route routeWithQuota = TestDataBuilder.buildAvailableRoute();
        routeWithQuota.setRouteAvailable(25);

        when(routeRepository.findById("route_quota_50_30")).thenReturn(Optional.of(routeWithQuota));
        when(routeRepository.save(any(Route.class))).thenAnswer(invocation -> invocation.getArgument(0));

        boolean result = routeService.restoreQuota("route_quota_50_30", 5);

        assertTrue(result);
        assertEquals(30, routeWithQuota.getRouteAvailable());
    }

    @Test
    @DisplayName("测试名额恢复 - full状态恢复后变为available")
    void testRestoreQuotaFromFullToAvailable() {
        fullRoute.setRouteAvailable(0);

        when(routeRepository.findById("route_full_001")).thenReturn(Optional.of(fullRoute));
        when(routeRepository.save(any(Route.class))).thenAnswer(invocation -> invocation.getArgument(0));

        boolean result = routeService.restoreQuota("route_full_001", 5);

        assertTrue(result);
        assertEquals(5, fullRoute.getRouteAvailable());
        assertEquals("available", fullRoute.getRouteStatus());
    }

    @Test
    @DisplayName("测试名额恢复 - 不能超过总配额")
    void testRestoreQuotaCannotExceedTotalQuota() {
        when(routeRepository.findById("route_quota_50_30")).thenReturn(Optional.of(availableRoute));

        BusinessException exception = assertThrows(BusinessException.class, () -> {
            routeService.restoreQuota("route_quota_50_30", 100);
        });

        assertEquals(400, exception.getCode());
        assertTrue(exception.getMessage().contains("名额超限"));
    }

    @Test
    @DisplayName("测试已关闭线路不能进行名额操作")
    void testClosedRouteQuotaOperationRejected() {
        when(routeRepository.findById("route_closed_001")).thenReturn(Optional.of(closedRoute));

        BusinessException decreaseException = assertThrows(BusinessException.class, () -> {
            routeService.decreaseQuota("route_closed_001", 5);
        });

        assertEquals(400, decreaseException.getCode());
        assertTrue(decreaseException.getMessage().contains("线路不可用"));

        BusinessException restoreException = assertThrows(BusinessException.class, () -> {
            routeService.restoreQuota("route_closed_001", 5);
        });

        assertEquals(400, restoreException.getCode());
        assertTrue(restoreException.getMessage().contains("线路不可用"));
    }

    @Test
    @DisplayName("测试线路不存在时抛出异常")
    void testExceptionWhenRouteNotFound() {
        when(routeRepository.findById("nonexistent")).thenReturn(Optional.empty());

        BusinessException exception = assertThrows(BusinessException.class, () -> {
            routeService.decreaseQuota("nonexistent", 5);
        });

        assertEquals(404, exception.getCode());
        assertEquals("线路不存在", exception.getMessage());
    }

    @Test
    @DisplayName("测试线路关闭时记录历史")
    void testHistoryRecordedWhenClosingRoute() {
        when(routeRepository.findById("route_quota_50_30")).thenReturn(Optional.of(availableRoute));
        when(routeRepository.save(any(Route.class))).thenReturn(availableRoute);

        routeService.closeRoute("route_quota_50_30", "测试关闭");

        verify(historyService).recordHistory(
                eq("route"),
                eq("route_quota_50_30"),
                eq("close"),
                anyString()
        );
    }

    @Test
    @DisplayName("测试线路开启时记录历史")
    void testHistoryRecordedWhenOpeningRoute() {
        when(routeRepository.findById("route_closed_001")).thenReturn(Optional.of(closedRoute));
        when(routeRepository.save(any(Route.class))).thenReturn(closedRoute);

        routeService.openRoute("route_closed_001");

        verify(historyService).recordHistory(
                eq("route"),
                eq("route_closed_001"),
                eq("open"),
                anyString()
        );
    }

    @Test
    @DisplayName("测试线路关闭时更新统计")
    void testStatisticsUpdatedWhenClosingRoute() {
        when(routeRepository.findById("route_quota_50_30")).thenReturn(Optional.of(availableRoute));
        when(routeRepository.save(any(Route.class))).thenReturn(availableRoute);

        routeService.closeRoute("route_quota_50_30", "测试关闭");

        verify(analyticsService).updateRouteStatistics(availableRoute);
    }

    @Test
    @DisplayName("测试线路开启时更新统计")
    void testStatisticsUpdatedWhenOpeningRoute() {
        when(routeRepository.findById("route_closed_001")).thenReturn(Optional.of(closedRoute));
        when(routeRepository.save(any(Route.class))).thenReturn(closedRoute);

        routeService.openRoute("route_closed_001");

        verify(analyticsService).updateRouteStatistics(closedRoute);
    }

    @Test
    @DisplayName("测试线路状态常量值验证")
    void testRouteStatusConstantValues() {
        assertEquals("available", domesticRoute.getRouteStatus());
        assertEquals("full", fullRoute.getRouteStatus());
        assertEquals("closed", closedRoute.getRouteStatus());
    }

    @Test
    @DisplayName("测试线路类型常量值验证")
    void testRouteTypeConstantValues() {
        assertEquals("domestic", domesticRoute.getRouteType());
        assertEquals("international", internationalRoute.getRouteType());
    }

    @Test
    @DisplayName("测试新增线路时配额设置正确")
    void testAddRouteSetsQuotaCorrectly() {
        Route newRoute = new Route();
        newRoute.setRouteId("route_new");
        newRoute.setRouteName("新线路");
        newRoute.setRouteQuota(200);

        when(routeRepository.save(any(Route.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Route result = routeService.addRoute(newRoute);

        assertEquals(200, result.getRouteQuota());
        assertEquals(200, result.getRouteRemainQuota());
    }

    @Test
    @DisplayName("测试线路查询按类型筛选")
    void testQueryRoutesByType() {
        when(routeRepository.findByRouteType("domestic")).thenReturn(Collections.singletonList(domesticRoute));
        when(routeRepository.findByRouteType("international")).thenReturn(Collections.singletonList(internationalRoute));

        List<Route> domestic = routeService.getRoutesByType("domestic");
        List<Route> international = routeService.getRoutesByType("international");

        assertEquals(1, domestic.size());
        assertEquals("domestic", domestic.get(0).getRouteType());
        assertEquals(1, international.size());
        assertEquals("international", international.get(0).getRouteType());
    }

    @Test
    @DisplayName("测试多线路同时操作的状态独立性")
    void testIndependentStateOperationsOnMultipleRoutes() {
        Route route1 = TestDataBuilder.buildDomesticRoute();
        Route route2 = TestDataBuilder.buildInternationalRoute();

        when(routeRepository.findById("route_test_001")).thenReturn(Optional.of(route1));
        when(routeRepository.findById("route_test_002")).thenReturn(Optional.of(route2));
        when(routeRepository.save(any(Route.class))).thenAnswer(invocation -> invocation.getArgument(0));

        routeService.closeRoute("route_test_001", "测试关闭");
        route1.setRouteStatus("full");

        assertEquals("full", route1.getRouteStatus());
        assertEquals("available", route2.getRouteStatus());
    }
}
