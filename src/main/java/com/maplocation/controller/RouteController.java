package com.maplocation.controller;

import com.maplocation.dto.ApiResponse;
import com.maplocation.dto.RoutePlanRequest;
import com.maplocation.dto.RoutePlanResponse;
import com.maplocation.model.Coordinates;
import com.maplocation.model.Route;
import com.maplocation.model.RouteTask;
import com.maplocation.service.RouteService;
import com.maplocation.service.RouteTypeConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/routes")
@RequiredArgsConstructor
public class RouteController {

    private final RouteService routeService;
    private final RouteTypeConfigService routeTypeConfigService;

    @PostMapping("/plan")
    public ApiResponse<RoutePlanResponse> planRoute(@RequestBody RoutePlanRequest request) {
        try {
            RoutePlanResponse response = routeService.planRoute(request);
            return ApiResponse.success(response);
        } catch (Exception e) {
            return ApiResponse.error(500, e.getMessage());
        }
    }

    @PostMapping("/plan/async")
    public ApiResponse<RoutePlanResponse> planRouteAsync(@RequestBody RoutePlanRequest request) {
        try {
            RoutePlanResponse response = routeService.planRouteAsync(request);
            return ApiResponse.success(response);
        } catch (Exception e) {
            return ApiResponse.error(500, e.getMessage());
        }
    }

    @PostMapping("/plan/async/waypoints")
    public ApiResponse<RoutePlanResponse> planRouteAsyncWithWaypoints(@RequestBody Map<String, Object> payload) {
        try {
            @SuppressWarnings("unchecked")
            List<Map<String, Double>> waypointsList = (List<Map<String, Double>>) payload.get("waypoints");
            String routeType = (String) payload.get("routeType");

            List<Coordinates> waypoints = waypointsList.stream()
                    .map(w -> new Coordinates(w.get("lat"), w.get("lng")))
                    .toList();

            RoutePlanResponse response = routeService.planRouteAsyncWithWaypoints(waypoints, routeType);
            return ApiResponse.success(response);
        } catch (Exception e) {
            return ApiResponse.error(500, e.getMessage());
        }
    }

    @GetMapping("/tasks/{taskId}/status")
    public ApiResponse<Map<String, Object>> getTaskStatus(@PathVariable String taskId) {
        RouteTask.TaskStatus status = routeService.getAsyncTaskStatus(taskId);
        if (status == null) {
            return ApiResponse.error(404, "Task not found");
        }
        return ApiResponse.success(Map.of(
                "taskId", taskId,
                "status", status.name()
        ));
    }

    @GetMapping("/types")
    public ApiResponse<List<com.maplocation.model.RouteTypeConfig>> getSupportedRouteTypes() {
        return ApiResponse.success(routeTypeConfigService.getAllConfigs());
    }

    @GetMapping("/{routeId}")
    public ApiResponse<Route> getRouteById(@PathVariable String routeId) {
        Route route = routeService.getRouteById(routeId);
        if (route == null) {
            return ApiResponse.error(404, "Route not found");
        }
        return ApiResponse.success(route);
    }
}
