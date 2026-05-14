package com.travelbooking.controller;

import com.travelbooking.dto.ApiResponse;
import com.travelbooking.dto.RouteSearchRequest;
import com.travelbooking.dto.RouteSearchResponse;
import com.travelbooking.model.Route;
import com.travelbooking.service.AnalyticsService;
import com.travelbooking.service.RouteService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/routes")
@RequiredArgsConstructor
public class RouteController {

    private final RouteService routeService;
    private final AnalyticsService analyticsService;

    @GetMapping("/search")
    public ApiResponse<RouteSearchResponse> searchRoutes(
            @RequestParam(required = false) String routeType,
            @RequestParam(required = false) String departureDate,
            @RequestParam(required = false) String routeStatus) {

        RouteSearchRequest request = new RouteSearchRequest();
        request.setRouteType(routeType);
        request.setDepartureDate(departureDate);
        request.setRouteStatus(routeStatus);

        RouteSearchResponse response = routeService.searchRoutes(request);
        return ApiResponse.success(response);
    }

    @GetMapping
    public ApiResponse<List<Route>> getAllRoutes() {
        return ApiResponse.success(routeService.getAllRoutes());
    }

    @GetMapping("/{id}")
    public ApiResponse<Route> getRouteById(@PathVariable String id) {
        return routeService.getRouteById(id)
                .map(ApiResponse::success)
                .orElse(ApiResponse.error(404, "线路不存在"));
    }

    @PostMapping
    public ApiResponse<Route> createRoute(@Valid @RequestBody Route route) {
        Route created = routeService.createRoute(route);
        analyticsService.incrementRouteCount();
        return ApiResponse.success(created);
    }

    @PutMapping("/{id}")
    public ApiResponse<Route> updateRoute(@PathVariable String id, @RequestBody Route route) {
        Route updated = routeService.updateRoute(id, route);
        return ApiResponse.success(updated);
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> deleteRoute(@PathVariable String id) {
        routeService.deleteRoute(id);
        return ApiResponse.success(null);
    }
}
