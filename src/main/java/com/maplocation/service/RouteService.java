package com.maplocation.service;

import com.maplocation.dto.RoutePlanRequest;
import com.maplocation.dto.RoutePlanResponse;
import com.maplocation.model.Coordinates;
import com.maplocation.model.Route;
import com.maplocation.model.RouteTask;
import com.maplocation.repository.RouteRepository;
import com.maplocation.util.GeoUtils;
import com.maplocation.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class RouteService {

    private static final Logger logger = LoggerFactory.getLogger(RouteService.class);

    private final RouteRepository routeRepository;
    private final AnalysisService analysisService;
    private final RedisRouteTaskQueue redisTaskQueue;
    private final RouteTypeConfigService routeTypeConfigService;

    public RoutePlanResponse planRoute(RoutePlanRequest request) {
        return planRouteSync(request);
    }

    public RoutePlanResponse planRouteSync(RoutePlanRequest request) {
        try {
            if (!GeoUtils.isValidCoordinates(request.getStartLocation()) ||
                !GeoUtils.isValidCoordinates(request.getEndLocation())) {
                throw new RuntimeException("Invalid coordinates");
            }

            String routeType = request.getRouteType() != null ? request.getRouteType() : routeTypeConfigService.getDefaultRouteType();

            if (!routeTypeConfigService.isRouteTypeSupported(routeType)) {
                throw new RuntimeException("Unsupported route type: " + routeType);
            }

            double distance = calculateRouteDistance(
                    request.getStartLocation(),
                    request.getEndLocation(),
                    routeType
            );

            int duration = calculateRouteDuration(distance, routeType);

            List<Coordinates> path = generateRoutePath(
                    request.getStartLocation(),
                    request.getEndLocation(),
                    routeType
            );

            Route route = Route.builder()
                    .routeId(IdGenerator.generateRouteId())
                    .startLocation(request.getStartLocation())
                    .endLocation(request.getEndLocation())
                    .routeType(routeType)
                    .routeDistance(distance)
                    .routeDuration(duration)
                    .routePath(path)
                    .calculatedAt(Instant.now())
                    .build();

            routeRepository.save(route);
            analysisService.incrementRouteCount();
            analysisService.updateAvgDistance(distance);

            return RoutePlanResponse.builder()
                    .routeId(route.getRouteId())
                    .routeDistance(distance)
                    .routeDuration(duration)
                    .routeType(routeType)
                    .routePath(path)
                    .build();

        } catch (Exception e) {
            logger.error("Route planning failed", e);
            throw new RuntimeException("Route planning failed: " + e.getMessage());
        }
    }

    public RoutePlanResponse planRouteAsync(RoutePlanRequest request) {
        try {
            if (!GeoUtils.isValidCoordinates(request.getStartLocation()) ||
                !GeoUtils.isValidCoordinates(request.getEndLocation())) {
                throw new RuntimeException("Invalid coordinates");
            }

            String routeId = IdGenerator.generateRouteId();
            String routeType = request.getRouteType() != null ? request.getRouteType() : routeTypeConfigService.getDefaultRouteType();

            if (!routeTypeConfigService.isRouteTypeSupported(routeType)) {
                throw new RuntimeException("Unsupported route type: " + routeType);
            }

            Route pendingRoute = Route.builder()
                    .routeId(routeId)
                    .startLocation(request.getStartLocation())
                    .endLocation(request.getEndLocation())
                    .routeType(routeType)
                    .routeDistance(0)
                    .routeDuration(0)
                    .routePath(new ArrayList<>())
                    .calculatedAt(null)
                    .build();

            routeRepository.save(pendingRoute);

            List<Coordinates> waypoints = new ArrayList<>();
            waypoints.add(request.getStartLocation());
            waypoints.add(request.getEndLocation());

            String taskId = redisTaskQueue.submitTask(routeType, waypoints, routeId);
            logger.info("Submitted async route task: {}", taskId);

            return RoutePlanResponse.builder()
                    .routeId(routeId)
                    .taskId(taskId)
                    .routeDistance(0)
                    .routeDuration(0)
                    .routeType(routeType)
                    .routePath(new ArrayList<>())
                    .async(true)
                    .build();

        } catch (Exception e) {
            logger.error("Async route planning submission failed", e);
            throw new RuntimeException("Route planning submission failed: " + e.getMessage());
        }
    }

    public RoutePlanResponse planRouteAsyncWithWaypoints(List<Coordinates> waypoints, String routeType) {
        try {
            if (waypoints == null || waypoints.size() < 2) {
                throw new RuntimeException("Route requires at least 2 waypoints");
            }

            for (Coordinates coords : waypoints) {
                if (!GeoUtils.isValidCoordinates(coords)) {
                    throw new RuntimeException("Invalid coordinates in waypoints");
                }
            }

            String finalRouteType = routeType != null ? routeType : routeTypeConfigService.getDefaultRouteType();
            if (!routeTypeConfigService.isRouteTypeSupported(finalRouteType)) {
                throw new RuntimeException("Unsupported route type: " + finalRouteType);
            }

            String routeId = IdGenerator.generateRouteId();

            Route pendingRoute = Route.builder()
                    .routeId(routeId)
                    .startLocation(waypoints.get(0))
                    .endLocation(waypoints.get(waypoints.size() - 1))
                    .routeType(finalRouteType)
                    .routeDistance(0)
                    .routeDuration(0)
                    .routeWaypoints(waypoints)
                    .routePath(new ArrayList<>())
                    .calculatedAt(null)
                    .build();

            routeRepository.save(pendingRoute);

            String taskId = redisTaskQueue.submitTask(finalRouteType, waypoints, routeId);
            logger.info("Submitted async multi-waypoint route task: {}", taskId);

            return RoutePlanResponse.builder()
                    .routeId(routeId)
                    .taskId(taskId)
                    .routeDistance(0)
                    .routeDuration(0)
                    .routeType(finalRouteType)
                    .routePath(new ArrayList<>())
                    .async(true)
                    .build();

        } catch (Exception e) {
            logger.error("Async multi-waypoint route planning failed", e);
            throw new RuntimeException("Route planning submission failed: " + e.getMessage());
        }
    }

    public RouteTask.TaskStatus getAsyncTaskStatus(String taskId) {
        return redisTaskQueue.getTaskStatus(taskId);
    }

    public double calculateRouteDistance(Coordinates start, Coordinates end, String routeType) {
        double directDistance = GeoUtils.calculateDistance(start, end);
        double routeFactor = routeTypeConfigService.getDistanceFactor(routeType);
        return directDistance * routeFactor;
    }

    public int calculateRouteDuration(double distance, String routeType) {
        double averageSpeedKmh = routeTypeConfigService.getAverageSpeedKmh(routeType);
        double speedMetersPerSecond = averageSpeedKmh * 1000.0 / 3600.0;
        return (int) Math.round(distance / speedMetersPerSecond);
    }

    public List<Coordinates> generateRoutePath(Coordinates start, Coordinates end, String routeType) {
        List<Coordinates> path = new ArrayList<>();
        path.add(start);

        int segments = 5;
        for (int i = 1; i < segments; i++) {
            double ratio = (double) i / segments;
            double lat = start.getLat() + (end.getLat() - start.getLat()) * ratio;
            double lng = start.getLng() + (end.getLng() - start.getLng()) * ratio;

            if ("driving".equals(routeType)) {
                lat += (Math.random() - 0.5) * 0.001;
                lng += (Math.random() - 0.5) * 0.001;
            }

            path.add(new Coordinates(lat, lng));
        }

        path.add(end);
        return path;
    }

    public Route getRouteById(String routeId) {
        return routeRepository.findById(routeId).orElse(null);
    }

    public List<String> getSupportedRouteTypes() {
        return routeTypeConfigService.getAllSupportedTypes();
    }
}
