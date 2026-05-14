package com.maplocation.service;

import com.maplocation.dto.NearbyRequest;
import com.maplocation.dto.NearbyResponse;
import com.maplocation.model.Coordinates;
import com.maplocation.model.Location;
import com.maplocation.model.NearbyQuery;
import com.maplocation.model.NearbyQueryTask;
import com.maplocation.repository.LocationRepository;
import com.maplocation.repository.NearbyQueryRepository;
import com.maplocation.util.GeoUtils;
import com.maplocation.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class NearbyService {

    private static final Logger logger = LoggerFactory.getLogger(NearbyService.class);

    private final LocationRepository locationRepository;
    private final NearbyQueryRepository nearbyQueryRepository;
    private final NearbyAsyncService nearbyAsyncService;
    private final LocationIndexService locationIndexService;

    public NearbyResponse findNearbyLocations(NearbyRequest request) {
        return findNearbySync(request);
    }

    public NearbyResponse findNearbySync(NearbyRequest request) {
        try {
            Coordinates center = request.getCenterLocation();
            double radius = request.getSearchRadius();
            int limit = request.getLimit() != null ? request.getLimit() : 20;
            String category = request.getCategory();

            List<NearbyResponse.LocationWithDistance> nearbyLocations = searchNearbyWithIndex(center, radius, category, limit);

            List<String> locationIds = nearbyLocations.stream()
                    .map(lwd -> lwd.getLocation().getLocationId())
                    .collect(Collectors.toList());

            NearbyQuery nearbyQuery = NearbyQuery.builder()
                    .nearbyId(IdGenerator.generateNearbyId())
                    .centerLocation(center)
                    .searchRadius(radius)
                    .nearbyLocations(locationIds)
                    .searchedAt(Instant.now())
                    .build();

            nearbyQueryRepository.save(nearbyQuery);

            return NearbyResponse.builder()
                    .nearbyLocations(nearbyLocations)
                    .build();

        } catch (Exception e) {
            logger.error("Nearby search failed", e);
            return NearbyResponse.builder()
                    .nearbyLocations(new ArrayList<>())
                    .build();
        }
    }

    public NearbyResponse findNearbyAsync(NearbyRequest request) {
        try {
            Coordinates center = request.getCenterLocation();
            double radius = request.getSearchRadius();
            String category = request.getCategory();

            String taskId = nearbyAsyncService.submitNearbyQueryAsync(center, radius, category);

            return NearbyResponse.builder()
                    .nearbyLocations(new ArrayList<>())
                    .taskId(taskId)
                    .async(true)
                    .build();

        } catch (Exception e) {
            logger.error("Async nearby submission failed", e);
            throw new RuntimeException("Async nearby query submission failed: " + e.getMessage());
        }
    }

    public NearbyQueryTask.TaskStatus getAsyncTaskStatus(String taskId) {
        return nearbyAsyncService.getTaskStatus(taskId);
    }

    public NearbyResponse getAsyncResults(String taskId) {
        List<Location> results = nearbyAsyncService.getResults(taskId);
        if (results == null) {
            return null;
        }

        NearbyQueryTask task = nearbyAsyncService.getTask(taskId);
        Coordinates center = task != null ? task.getCenterCoordinates() : null;

        List<NearbyResponse.LocationWithDistance> locationsWithDistance = results.stream()
                .map(l -> NearbyResponse.LocationWithDistance.builder()
                        .location(l)
                        .distance(center != null && l.getLocationCoordinates() != null
                                ? GeoUtils.calculateDistance(center, l.getLocationCoordinates())
                                : 0.0)
                        .build())
                .collect(Collectors.toList());

        return NearbyResponse.builder()
                .nearbyLocations(locationsWithDistance)
                .taskId(taskId)
                .build();
    }

    private List<NearbyResponse.LocationWithDistance> searchNearbyWithIndex(Coordinates center, double radius, String category, int limit) {
        List<Location> candidates;

        if (category != null && !category.isEmpty()) {
            var indexResults = locationIndexService.searchByCategory(category);
            if (indexResults != null && !indexResults.isEmpty()) {
                logger.debug("Using category index for nearby search: {}", category);
                candidates = indexResults.stream()
                        .map(idx -> {
                            Location loc = new Location();
                            loc.setLocationId(idx.getLocationId());
                            loc.setLocationName(idx.getLocationName());
                            loc.setLocationCoordinates(idx.getLocationCoordinates());
                            loc.setLocationCategory(idx.getLocationCategory());
                            loc.setLocationType(idx.getLocationType());
                            loc.setLocationTags(idx.getLocationTags());
                            return loc;
                        })
                        .collect(Collectors.toList());
            } else {
                candidates = locationRepository.findByCategory(category);
            }
        } else {
            var geoResults = locationIndexService.searchByRange(center, radius);
            if (geoResults != null && !geoResults.isEmpty()) {
                logger.debug("Using geo index for nearby search");
                candidates = geoResults.stream()
                        .map(idx -> {
                            Location loc = new Location();
                            loc.setLocationId(idx.getLocationId());
                            loc.setLocationName(idx.getLocationName());
                            loc.setLocationCoordinates(idx.getLocationCoordinates());
                            loc.setLocationCategory(idx.getLocationCategory());
                            return loc;
                        })
                        .collect(Collectors.toList());
            } else {
                candidates = locationRepository.findAll();
            }
        }

        return candidates.stream()
                .filter(l -> l.getLocationCoordinates() != null)
                .filter(l -> GeoUtils.isWithinRadius(center, l.getLocationCoordinates(), radius))
                .map(l -> NearbyResponse.LocationWithDistance.builder()
                        .location(l)
                        .distance(GeoUtils.calculateDistance(center, l.getLocationCoordinates()))
                        .build())
                .sorted(Comparator.comparingDouble(NearbyResponse.LocationWithDistance::getDistance))
                .limit(limit)
                .collect(Collectors.toList());
    }
}
