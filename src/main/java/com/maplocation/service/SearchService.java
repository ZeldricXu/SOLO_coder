package com.maplocation.service;

import com.maplocation.dto.SearchRequest;
import com.maplocation.dto.SearchResponse;
import com.maplocation.model.Coordinates;
import com.maplocation.model.Location;
import com.maplocation.model.LocationIndex;
import com.maplocation.repository.LocationRepository;
import com.maplocation.util.GeoUtils;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SearchService {

    private static final Logger logger = LoggerFactory.getLogger(SearchService.class);

    private final LocationRepository locationRepository;
    private final LocationIndexService locationIndexService;
    private final AnalysisService analysisService;
    private final HistoryService historyService;

    public SearchResponse searchLocations(SearchRequest request) {
        String searchType = request.getSearchType() != null ? request.getSearchType() : "keyword";
        List<Location> results = new ArrayList<>();

        try {
            if ("keyword".equals(searchType)) {
                results = searchByKeywordWithIndex(request);
            } else if ("range".equals(searchType)) {
                results = searchByRangeWithIndex(request);
            } else {
                results = locationRepository.findAll();
            }

            if (request.getCategory() != null) {
                results = results.stream()
                        .filter(l -> request.getCategory().equals(l.getLocationCategory()))
                        .collect(Collectors.toList());
            }

            if (request.getLocationType() != null) {
                results = results.stream()
                        .filter(l -> request.getLocationType().equals(l.getLocationType()))
                        .collect(Collectors.toList());
            }

            int page = request.getPage() != null ? request.getPage() : 0;
            int size = request.getSize() != null ? request.getSize() : 20;
            int totalCount = results.size();
            int fromIndex = Math.min(page * size, results.size());
            int toIndex = Math.min(fromIndex + size, results.size());
            List<Location> pagedResults = results.subList(fromIndex, toIndex);

            List<String> locationIds = pagedResults.stream()
                    .map(Location::getLocationId)
                    .collect(Collectors.toList());

            analysisService.incrementQueryCount();
            analysisService.updateLocationHotness(locationIds);
            historyService.recordSearchHistory(request, locationIds);

            return SearchResponse.builder()
                    .locations(pagedResults)
                    .totalCount(totalCount)
                    .page(page)
                    .size(size)
                    .build();

        } catch (Exception e) {
            logger.error("Search failed", e);
            return SearchResponse.builder()
                    .locations(new ArrayList<>())
                    .totalCount(0)
                    .page(0)
                    .size(20)
                    .build();
        }
    }

    private List<Location> searchByKeywordWithIndex(SearchRequest request) {
        String keyword = request.getKeyword();
        if (keyword == null || keyword.isEmpty()) {
            return locationRepository.findAll();
        }

        List<LocationIndex> indexResults = locationIndexService.searchByKeyword(keyword);

        if (!indexResults.isEmpty()) {
            logger.debug("Found {} results in keyword index for '{}'", indexResults.size(), keyword);
            return indexResults.stream()
                    .map(this::convertIndexToLocation)
                    .collect(Collectors.toList());
        }

        logger.debug("Index miss for keyword '{}', falling back to database", keyword);
        return searchByKeywordFallback(request);
    }

    private List<Location> searchByKeywordFallback(SearchRequest request) {
        String keyword = request.getKeyword();

        List<Location> keywordResults = locationRepository.searchByKeyword(keyword);

        if (keywordResults.isEmpty()) {
            keywordResults = locationRepository.findAll().stream()
                    .filter(l -> (l.getLocationName() != null && l.getLocationName().toLowerCase().contains(keyword.toLowerCase())) ||
                                 (l.getLocationCategory() != null && l.getLocationCategory().toLowerCase().contains(keyword.toLowerCase())) ||
                                 (l.getLocationTags() != null && l.getLocationTags().stream().anyMatch(t -> t.toLowerCase().contains(keyword.toLowerCase()))))
                    .collect(Collectors.toList());
        }

        keywordResults.sort((l1, l2) -> {
            int score1 = calculateMatchScore(l1, keyword);
            int score2 = calculateMatchScore(l2, keyword);
            return Integer.compare(score2, score1);
        });

        return keywordResults;
    }

    private List<Location> searchByRangeWithIndex(SearchRequest request) {
        Coordinates center = request.getCenterLocation();
        Double radius = request.getSearchRadius();

        if (center == null || radius == null) {
            return new ArrayList<>();
        }

        List<LocationIndex> indexResults = locationIndexService.searchByRange(center, radius);

        if (!indexResults.isEmpty()) {
            logger.debug("Found {} results in geo index", indexResults.size());
            return indexResults.stream()
                    .map(this::convertIndexToLocation)
                    .collect(Collectors.toList());
        }

        logger.debug("Geo index miss, falling back to database");
        return searchByRangeFallback(request);
    }

    private List<Location> searchByRangeFallback(SearchRequest request) {
        Coordinates center = request.getCenterLocation();
        Double radius = request.getSearchRadius();

        if (center == null || radius == null) {
            return new ArrayList<>();
        }

        List<Location> allLocations = locationRepository.findAll();

        List<Location> withinRange = allLocations.stream()
                .filter(l -> l.getLocationCoordinates() != null)
                .filter(l -> GeoUtils.isWithinRadius(center, l.getLocationCoordinates(), radius))
                .collect(Collectors.toList());

        withinRange.sort(Comparator.comparingDouble(
                l -> GeoUtils.calculateDistance(center, l.getLocationCoordinates())
        ));

        return withinRange;
    }

    private int calculateMatchScore(Location location, String keyword) {
        int score = 0;
        String lowerKeyword = keyword.toLowerCase();

        if (location.getLocationName() != null && location.getLocationName().toLowerCase().contains(lowerKeyword)) {
            score += 10;
        }
        if (location.getLocationCategory() != null && location.getLocationCategory().toLowerCase().contains(lowerKeyword)) {
            score += 5;
        }
        if (location.getLocationTags() != null) {
            for (String tag : location.getLocationTags()) {
                if (tag.toLowerCase().contains(lowerKeyword)) {
                    score += 3;
                }
            }
        }
        return score;
    }

    private Location convertIndexToLocation(LocationIndex index) {
        Location location = new Location();
        location.setLocationId(index.getLocationId());
        location.setLocationName(index.getLocationName());
        location.setLocationType(index.getLocationType());
        location.setLocationCategory(index.getLocationCategory());
        location.setLocationTags(index.getLocationTags());
        location.setLocationCoordinates(index.getLocationCoordinates());
        return location;
    }
}
