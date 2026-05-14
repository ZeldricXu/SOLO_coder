package com.maplocation.service;

import com.maplocation.model.Coordinates;
import com.maplocation.model.Route;
import com.maplocation.model.RouteTask;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class RedisRouteTaskWorker {

    private final RedisRouteTaskQueue taskQueue;
    private final RouteTypeConfigService routeTypeConfigService;
    private final DistanceService distanceService;
    private final com.maplocation.repository.RouteRepository routeRepository;

    @Scheduled(fixedDelay = 1000)
    public void processQueue() {
        while (true) {
            Optional<String> taskIdOpt = taskQueue.pollTask();
            if (taskIdOpt.isEmpty()) {
                break;
            }

            String taskId = taskIdOpt.get();
            processTaskAsync(taskId);
        }
    }

    @Async("routeTaskExecutor")
    public void processTaskAsync(String taskId) {
        log.info("Processing route task: {}", taskId);

        try {
            RouteTask task = taskQueue.getTask(taskId);
            if (task == null) {
                log.warn("Task not found: {}", taskId);
                return;
            }

            Route route = processRouteTask(task);
            if (route != null) {
                routeRepository.save(route);
                taskQueue.markCompleted(taskId);
                log.info("Route task completed: {}", taskId);
            } else {
                taskQueue.markFailed(taskId, "Route calculation returned null");
            }
        } catch (Exception e) {
            log.error("Failed to process route task: {}", taskId, e);
            taskQueue.markFailed(taskId, e.getMessage());
        }
    }

    private Route processRouteTask(RouteTask task) {
        List<Coordinates> waypoints = task.getWaypoints();
        String routeType = task.getRouteType();

        if (waypoints == null || waypoints.size() < 2) {
            throw new IllegalArgumentException("Route requires at least 2 waypoints");
        }

        double totalDistanceMeters = calculateTotalDistance(waypoints);
        int durationMinutes = calculateDuration(totalDistanceMeters, routeType);
        List<Coordinates> path = generatePathPoints(waypoints);

        Route route = new Route();
        route.setRouteId(task.getRouteId());
        route.setRouteType(routeType);
        route.setRouteWaypoints(waypoints);
        route.setRouteStartPoint(waypoints.get(0));
        route.setRouteEndPoint(waypoints.get(waypoints.size() - 1));
        route.setRouteDistanceMeters(totalDistanceMeters);
        route.setRouteTimeSeconds((long) (durationMinutes * 60));
        route.setRoutePath(path);
        route.setRouteCreatedAt(java.time.Instant.now());

        return route;
    }

    private double calculateTotalDistance(List<Coordinates> waypoints) {
        double totalDistance = 0;
        for (int i = 0; i < waypoints.size() - 1; i++) {
            totalDistance += distanceService.calculateDirectDistance(
                    waypoints.get(i),
                    waypoints.get(i + 1)
            );
        }
        return totalDistance;
    }

    private int calculateDuration(double distanceMeters, String routeType) {
        double averageSpeedKmh = routeTypeConfigService.getAverageSpeedKmh(routeType);
        double distanceKm = distanceMeters / 1000;
        return (int) Math.ceil((distanceKm / averageSpeedKmh) * 60);
    }

    private List<Coordinates> generatePathPoints(List<Coordinates> waypoints) {
        return waypoints;
    }
}
