package com.maplocation.service;

import com.maplocation.model.Location;
import com.maplocation.model.LocationQueryCount;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class RecommendationService {

    private final AnalysisService analysisService;
    private final LocationService locationService;

    public List<Location> getRecommendedLocations(String locationId, int limit) {
        Location location = locationService.getLocationById(locationId).orElse(null);
        if (location == null) {
            return getHotLocations(limit);
        }

        String category = location.getLocationCategory();
        List<String> tags = location.getLocationTags();

        List<Location> allLocations = locationService.getAllLocations();

        List<Location> similarLocations = allLocations.stream()
                .filter(l -> !l.getLocationId().equals(locationId))
                .filter(l -> category != null && category.equals(l.getLocationCategory()))
                .collect(Collectors.toList());

        if (similarLocations.isEmpty() && tags != null && !tags.isEmpty()) {
            similarLocations = allLocations.stream()
                    .filter(l -> !l.getLocationId().equals(locationId))
                    .filter(l -> l.getLocationTags() != null &&
                                 l.getLocationTags().stream().anyMatch(tags::contains))
                    .collect(Collectors.toList());
        }

        if (similarLocations.isEmpty()) {
            return getHotLocations(limit);
        }

        return similarLocations.stream()
                .limit(limit)
                .collect(Collectors.toList());
    }

    public List<Location> getHotLocations(int limit) {
        List<LocationQueryCount> hotCounts = analysisService.getHotLocations();

        if (hotCounts.isEmpty()) {
            List<Location> all = locationService.getAllLocations();
            return all.stream()
                    .limit(limit)
                    .collect(Collectors.toList());
        }

        List<String> hotIds = hotCounts.stream()
                .map(LocationQueryCount::getLocationId)
                .limit(limit)
                .collect(Collectors.toList());

        List<Location> locations = locationService.getLocationsByIds(hotIds);

        List<Location> orderedResult = new ArrayList<>();
        for (String id : hotIds) {
            locations.stream()
                    .filter(l -> l.getLocationId().equals(id))
                    .findFirst()
                    .ifPresent(orderedResult::add);
        }

        return orderedResult;
    }

    public List<Location> getRecommendationsByCategory(String category, int limit) {
        List<Location> categoryLocations = locationService.getLocationsByCategory(category);
        return categoryLocations.stream()
                .limit(limit)
                .collect(Collectors.toList());
    }
}
