package com.maplocation.service;

import com.maplocation.dto.RoutePlanRequest;
import com.maplocation.model.Coordinates;
import com.maplocation.model.Route;
import com.maplocation.repository.RouteRepository;
import com.maplocation.util.GeoUtils;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class RouteTaskWorker {

    private static final Logger logger = LoggerFactory.getLogger(RouteTaskWorker.class);

    private final RouteRepository routeRepository;
    private final AnalysisService analysisService;
    private final AsyncRouteTaskQueue taskQueue;

    private static final double DRIVING_SPEED = 16.67;
    private static final double WALKING_SPEED = 1.4;
    private static final double TRANSIT_SPEED = 5.0;

    @Async
    public void processPendingTasks() {
        while (!taskQueue.isEmpty()) {
            AsyncRouteTaskQueue.RouteTask task = taskQueue.pollTask();
            if (task != null) {
                try {
                    logger.info("Processing route task: {}", task.getTaskId());
                    executeRouteCalculation(task);
                    task.setStatus(AsyncRouteTaskQueue.TaskStatus.COMPLETED);
                    logger.info("Route task completed: {}", task.getTaskId());
                } catch (Exception e) {
                    logger.error("Route task failed: {}", task.getTaskId(), e);
                    task.setStatus(AsyncRouteTaskQueue.TaskStatus.FAILED);
                }
            }
        }
    }

    @Async
    public void processSingleTask(AsyncRouteTaskQueue.RouteTask task) {
        try {
            logger.info("Processing route task: {}", task.getTaskId());
            executeRouteCalculation(task);
            task.setStatus(AsyncRouteTaskQueue.TaskStatus.COMPLETED);
            logger.info("Route task completed: {}", task.getTaskId());
        } catch (Exception e) {
            logger.error("Route task failed: {}", task.getTaskId(), e);
            task.setStatus(AsyncRouteTaskQueue.TaskStatus.FAILED);
        }
    }

    public void executeRouteCalculation(AsyncRouteTaskQueue.RouteTask task) {
        RoutePlanRequest request = task.getRequest();
        String routeId = task.getRouteId();

        Optional<Route> existingRoute = routeRepository.findById(routeId);
        if (existingRoute.isEmpty()) {
            logger.warn("Route not found for task: {}", routeId);
            return;
        }

        String routeType = request.getRouteType() != null ? request.getRouteType() : "driving";

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

        Route route = existingRoute.get();
        route.setRouteDistance(distance);
        route.setRouteDuration(duration);
        route.setRoutePath(path);
        route.setCalculatedAt(Instant.now());

        routeRepository.save(route);
        analysisService.incrementRouteCount();
        analysisService.updateAvgDistance(distance);
    }

    public double calculateRouteDistance(Coordinates start, Coordinates end, String routeType) {
        double directDistance = GeoUtils.calculateDistance(start, end);

        double routeFactor = switch (routeType) {
            case "driving" -> 1.3;
            case "walking" -> 1.1;
            case "transit" -> 1.4;
            default -> 1.3;
        };

        return directDistance * routeFactor;
    }

    public int calculateRouteDuration(double distance, String routeType) {
        double speed = switch (routeType) {
            case "driving" -> DRIVING_SPEED;
            case "walking" -> WALKING_SPEED;
            case "transit" -> TRANSIT_SPEED;
            default -> DRIVING_SPEED;
        };

        return (int) Math.round(distance / speed);
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

    public Route calculateComplexRoute(List<Coordinates> waypoints, String routeType) {
        if (waypoints == null || waypoints.size() < 2) {
            throw new RuntimeException("At least 2 waypoints required");
        }

        double totalDistance = 0;
        int totalDuration = 0;
        List<Coordinates> fullPath = new ArrayList<>();

        for (int i = 0; i < waypoints.size() - 1; i++) {
            Coordinates start = waypoints.get(i);
            Coordinates end = waypoints.get(i + 1);

            double segmentDistance = calculateRouteDistance(start, end, routeType);
            totalDistance += segmentDistance;
            totalDuration += calculateRouteDuration(segmentDistance, routeType);

            List<Coordinates> segmentPath = generateRoutePath(start, end, routeType);
            if (i > 0 && !fullPath.isEmpty()) {
                segmentPath = segmentPath.subList(1, segmentPath.size());
            }
            fullPath.addAll(segmentPath);
        }

        return Route.builder()
                .startLocation(waypoints.get(0))
                .endLocation(waypoints.get(waypoints.size() - 1))
                .routeType(routeType)
                .routeDistance(totalDistance)
                .routeDuration(totalDuration)
                .routePath(fullPath)
                .calculatedAt(Instant.now())
                .build();
    }
}
