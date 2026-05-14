package com.maplocation.service;

import com.maplocation.model.Coordinates;
import com.maplocation.model.Location;
import com.maplocation.model.NearbyQueryTask;
import com.maplocation.util.GeoUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class NearbyAsyncService {

    private final Map<String, NearbyQueryTask> taskCache = new ConcurrentHashMap<>();
    private final LocationService locationService;
    private final LocationIndexService locationIndexService;

    public String submitNearbyQueryAsync(Coordinates center, double radiusMeters, String category) {
        String taskId = "nb_" + System.currentTimeMillis() + "_" + (int) (Math.random() * 10000);
        String queryId = "qb_" + taskId;

        NearbyQueryTask task = NearbyQueryTask.builder()
                .taskId(taskId)
                .queryId(queryId)
                .centerCoordinates(center)
                .radiusMeters(radiusMeters)
                .category(category)
                .status(NearbyQueryTask.TaskStatus.PENDING)
                .submittedAt(Instant.now())
                .build();

        taskCache.put(taskId, task);
        taskCache.put(queryId, task);

        computeNearbyAsync(taskId);

        log.info("Submitted async nearby query: {}", taskId);
        return taskId;
    }

    @Async("nearbyTaskExecutor")
    public void computeNearbyAsync(String taskId) {
        NearbyQueryTask task = taskCache.get(taskId);
        if (task == null) {
            return;
        }

        try {
            task.setStatus(NearbyQueryTask.TaskStatus.COMPUTING);

            List<Location> results = computeNearbyResults(task);

            task.setResultLocations(results);
            task.setStatus(NearbyQueryTask.TaskStatus.COMPLETED);
            task.setCompletedAt(Instant.now());

            log.info("Completed nearby query: {}", taskId);
        } catch (Exception e) {
            task.setStatus(NearbyQueryTask.TaskStatus.FAILED);
            task.setCompletedAt(Instant.now());
            log.error("Failed to compute nearby query: {}", taskId, e);
        }
    }

    private List<Location> computeNearbyResults(NearbyQueryTask task) {
        List<Location> candidates;

        if (task.getCategory() != null && !task.getCategory().isEmpty()) {
            var indexResults = locationIndexService.searchByCategory(task.getCategory());
            candidates = new ArrayList<>();
            for (var idx : indexResults) {
                Location loc = new Location();
                loc.setLocationId(idx.getLocationId());
                loc.setLocationName(idx.getLocationName());
                loc.setLocationCoordinates(idx.getLocationCoordinates());
                loc.setLocationCategory(idx.getLocationCategory());
                candidates.add(loc);
            }
        } else {
            candidates = locationService.getAllLocations();
        }

        List<Location> filteredResults = new ArrayList<>();
        for (Location location : candidates) {
            if (location.getLocationCoordinates() == null) {
                continue;
            }

            double distance = GeoUtils.calculateDistance(
                    task.getCenterCoordinates(),
                    location.getLocationCoordinates()
            );

            if (distance <= task.getRadiusMeters()) {
                filteredResults.add(location);
            }
        }

        filteredResults.sort((a, b) -> {
            double distA = GeoUtils.calculateDistance(task.getCenterCoordinates(), a.getLocationCoordinates());
            double distB = GeoUtils.calculateDistance(task.getCenterCoordinates(), b.getLocationCoordinates());
            return Double.compare(distA, distB);
        });

        return filteredResults;
    }

    public NearbyQueryTask.TaskStatus getTaskStatus(String taskId) {
        NearbyQueryTask task = taskCache.get(taskId);
        return task != null ? task.getStatus() : null;
    }

    public NearbyQueryTask getTask(String taskId) {
        return taskCache.get(taskId);
    }

    public List<Location> getResults(String taskId) {
        NearbyQueryTask task = taskCache.get(taskId);
        return task != null ? task.getResultLocations() : null;
    }

    public void removeTask(String taskId) {
        NearbyQueryTask task = taskCache.get(taskId);
        if (task != null) {
            taskCache.remove(taskId);
            if (task.getQueryId() != null) {
                taskCache.remove(task.getQueryId());
            }
        }
    }
}
