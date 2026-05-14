package com.maplocation.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maplocation.model.Coordinates;
import com.maplocation.model.Location;
import com.maplocation.model.LocationIndex;
import com.maplocation.util.GeoUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class LocationIndexService {

    private static final String INDEX_KEY_PREFIX = "loc_idx:";
    private static final String KEYWORD_INDEX_KEY = "loc_idx:keywords";
    private static final String CATEGORY_INDEX_KEY = "loc_idx:categories";
    private static final String TYPE_INDEX_KEY = "loc_idx:types";
    private static final String GEO_INDEX_KEY = "loc_idx:geo";
    private static final String INDEX_META_KEY = "loc_idx:meta";
    private static final long INDEX_TTL_SECONDS = 3600 * 24;

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    public void buildIndex(Location location) {
        try {
            LocationIndex index = LocationIndex.builder()
                    .locationId(location.getLocationId())
                    .locationName(location.getLocationName())
                    .locationType(location.getLocationType())
                    .locationCategory(location.getLocationCategory())
                    .locationTags(location.getLocationTags())
                    .locationCoordinates(location.getLocationCoordinates())
                    .indexedAt(Instant.now())
                    .build();

            String indexJson = objectMapper.writeValueAsString(index);
            String indexKey = INDEX_KEY_PREFIX + location.getLocationId();

            redisTemplate.opsForValue().set(indexKey, indexJson, INDEX_TTL_SECONDS, TimeUnit.SECONDS);

            indexKeywords(location);
            indexCategory(location);
            indexType(location);
            indexGeo(location);

            updateIndexMeta();

            log.debug("Built index for location: {}", location.getLocationId());
        } catch (JsonProcessingException e) {
            log.error("Failed to build index for location: {}", location.getLocationId(), e);
        }
    }

    private void indexKeywords(Location location) {
        Set<String> keywords = extractKeywords(location);
        for (String keyword : keywords) {
            String keywordKey = KEYWORD_INDEX_KEY + ":" + keyword.toLowerCase();
            redisTemplate.opsForSet().add(keywordKey, location.getLocationId());
            redisTemplate.expire(keywordKey, INDEX_TTL_SECONDS, TimeUnit.SECONDS);
        }
    }

    private Set<String> extractKeywords(Location location) {
        Set<String> keywords = new HashSet<>();

        if (location.getLocationName() != null) {
            keywords.addAll(Arrays.asList(location.getLocationName().split("[\\s,，。、]+")));
            keywords.add(location.getLocationName().toLowerCase());
        }

        if (location.getLocationCategory() != null) {
            keywords.add(location.getLocationCategory().toLowerCase());
        }

        if (location.getLocationTags() != null) {
            for (String tag : location.getLocationTags()) {
                keywords.add(tag.toLowerCase());
            }
        }

        return keywords.stream()
                .filter(k -> k.length() >= 2)
                .collect(Collectors.toSet());
    }

    private void indexCategory(Location location) {
        if (location.getLocationCategory() != null) {
            String categoryKey = CATEGORY_INDEX_KEY + ":" + location.getLocationCategory();
            redisTemplate.opsForSet().add(categoryKey, location.getLocationId());
            redisTemplate.expire(categoryKey, INDEX_TTL_SECONDS, TimeUnit.SECONDS);
        }
    }

    private void indexType(Location location) {
        if (location.getLocationType() != null) {
            String typeKey = TYPE_INDEX_KEY + ":" + location.getLocationType();
            redisTemplate.opsForSet().add(typeKey, location.getLocationId());
            redisTemplate.expire(typeKey, INDEX_TTL_SECONDS, TimeUnit.SECONDS);
        }
    }

    private void indexGeo(Location location) {
        if (location.getLocationCoordinates() != null) {
            try {
                String geoValue = location.getLocationId();
                redisTemplate.opsForGeo().add(
                        GEO_INDEX_KEY,
                        new org.springframework.data.geo.Point(
                                location.getLocationCoordinates().getLng(),
                                location.getLocationCoordinates().getLat()
                        ),
                        geoValue
                );
            } catch (Exception e) {
                log.warn("Failed to index geo for location: {}", location.getLocationId(), e);
            }
        }
    }

    public void updateIndex(Location location) {
        removeFromIndex(location.getLocationId());
        buildIndex(location);
    }

    public void removeFromIndex(String locationId) {
        String indexKey = INDEX_KEY_PREFIX + locationId;

        LocationIndex existingIndex = getIndex(locationId);
        if (existingIndex != null) {
            if (existingIndex.getLocationCategory() != null) {
                String categoryKey = CATEGORY_INDEX_KEY + ":" + existingIndex.getLocationCategory();
                redisTemplate.opsForSet().remove(categoryKey, locationId);
            }
            if (existingIndex.getLocationType() != null) {
                String typeKey = TYPE_INDEX_KEY + ":" + existingIndex.getLocationType();
                redisTemplate.opsForSet().remove(typeKey, locationId);
            }

            Set<String> keywords = extractKeywordsFromIndex(existingIndex);
            for (String keyword : keywords) {
                String keywordKey = KEYWORD_INDEX_KEY + ":" + keyword.toLowerCase();
                redisTemplate.opsForSet().remove(keywordKey, locationId);
            }

            try {
                redisTemplate.opsForGeo().remove(GEO_INDEX_KEY, locationId);
            } catch (Exception e) {
                log.warn("Failed to remove geo index for location: {}", locationId, e);
            }
        }

        redisTemplate.delete(indexKey);
        updateIndexMeta();
    }

    private Set<String> extractKeywordsFromIndex(LocationIndex index) {
        Set<String> keywords = new HashSet<>();

        if (index.getLocationName() != null) {
            keywords.addAll(Arrays.asList(index.getLocationName().split("[\\s,，。、]+")));
            keywords.add(index.getLocationName().toLowerCase());
        }

        if (index.getLocationCategory() != null) {
            keywords.add(index.getLocationCategory().toLowerCase());
        }

        if (index.getLocationTags() != null) {
            for (String tag : index.getLocationTags()) {
                keywords.add(tag.toLowerCase());
            }
        }

        return keywords;
    }

    public LocationIndex getIndex(String locationId) {
        String indexKey = INDEX_KEY_PREFIX + locationId;
        String indexJson = redisTemplate.opsForValue().get(indexKey);
        if (indexJson == null) {
            return null;
        }
        try {
            return objectMapper.readValue(indexJson, LocationIndex.class);
        } catch (JsonProcessingException e) {
            log.error("Failed to parse index for location: {}", locationId, e);
            return null;
        }
    }

    public List<LocationIndex> searchByKeyword(String keyword) {
        String keywordKey = KEYWORD_INDEX_KEY + ":" + keyword.toLowerCase();
        Set<String> locationIds = redisTemplate.opsForSet().members(keywordKey);

        if (locationIds == null || locationIds.isEmpty()) {
            return Collections.emptyList();
        }

        List<LocationIndex> results = new ArrayList<>();
        for (String locationId : locationIds) {
            LocationIndex index = getIndex(locationId);
            if (index != null) {
                index.setMatchScore(calculateMatchScore(index, keyword));
                results.add(index);
            }
        }

        results.sort((a, b) -> Integer.compare(b.getMatchScore(), a.getMatchScore()));
        return results;
    }

    public List<LocationIndex> searchByCategory(String category) {
        String categoryKey = CATEGORY_INDEX_KEY + ":" + category;
        Set<String> locationIds = redisTemplate.opsForSet().members(categoryKey);

        if (locationIds == null || locationIds.isEmpty()) {
            return Collections.emptyList();
        }

        List<LocationIndex> results = new ArrayList<>();
        for (String locationId : locationIds) {
            LocationIndex index = getIndex(locationId);
            if (index != null) {
                results.add(index);
            }
        }
        return results;
    }

    public List<LocationIndex> searchByType(String type) {
        String typeKey = TYPE_INDEX_KEY + ":" + type;
        Set<String> locationIds = redisTemplate.opsForSet().members(typeKey);

        if (locationIds == null || locationIds.isEmpty()) {
            return Collections.emptyList();
        }

        List<LocationIndex> results = new ArrayList<>();
        for (String locationId : locationIds) {
            LocationIndex index = getIndex(locationId);
            if (index != null) {
                results.add(index);
            }
        }
        return results;
    }

    public List<LocationIndex> searchByRange(Coordinates center, double radiusMeters) {
        try {
            org.springframework.data.geo.Distance distance =
                    new org.springframework.data.geo.Distance(radiusMeters / 1000, org.springframework.data.geo.Metrics.KILOMETERS);

            org.springframework.data.geo.Circle circle = new org.springframework.data.geo.Circle(
                    new org.springframework.data.geo.Point(center.getLng(), center.getLat()),
                    distance
            );

            var results = redisTemplate.opsForGeo().radius(GEO_INDEX_KEY, circle);

            if (results == null || results.getContent().isEmpty()) {
                return Collections.emptyList();
            }

            List<LocationIndex> indexedResults = new ArrayList<>();
            for (var geoResult : results.getContent()) {
                String locationId = geoResult.getContent().getName();
                LocationIndex index = getIndex(locationId);
                if (index != null) {
                    double dist = GeoUtils.calculateDistance(center, index.getLocationCoordinates());
                    index.setMatchScore((int) (10000 - dist));
                    indexedResults.add(index);
                }
            }

            indexedResults.sort((a, b) -> Integer.compare(b.getMatchScore(), a.getMatchScore()));
            return indexedResults;

        } catch (Exception e) {
            log.error("Failed to search by range", e);
            return Collections.emptyList();
        }
    }

    private int calculateMatchScore(LocationIndex index, String keyword) {
        int score = 0;
        String lowerKeyword = keyword.toLowerCase();

        if (index.getLocationName() != null && index.getLocationName().toLowerCase().contains(lowerKeyword)) {
            score += 10;
            if (index.getLocationName().equalsIgnoreCase(keyword)) {
                score += 20;
            }
        }

        if (index.getLocationCategory() != null && index.getLocationCategory().toLowerCase().contains(lowerKeyword)) {
            score += 5;
        }

        if (index.getLocationTags() != null) {
            for (String tag : index.getLocationTags()) {
                if (tag.toLowerCase().contains(lowerKeyword)) {
                    score += 3;
                }
            }
        }

        return score;
    }

    public boolean hasIndex(String locationId) {
        String indexKey = INDEX_KEY_PREFIX + locationId;
        return Boolean.TRUE.equals(redisTemplate.hasKey(indexKey));
    }

    public Map<String, Object> getIndexMetadata() {
        Map<String, Object> meta = new HashMap<>();

        String lastUpdate = redisTemplate.opsForValue().get(INDEX_META_KEY + ":lastUpdate");
        meta.put("lastUpdate", lastUpdate);

        Long keywordCount = redisTemplate.keys(KEYWORD_INDEX_KEY + ":*") != null
                ? redisTemplate.keys(KEYWORD_INDEX_KEY + ":*").size()
                : 0;
        meta.put("keywordCount", keywordCount);

        Long categoryCount = redisTemplate.keys(CATEGORY_INDEX_KEY + ":*") != null
                ? redisTemplate.keys(CATEGORY_INDEX_KEY + ":*").size()
                : 0;
        meta.put("categoryCount", categoryCount);

        Long locationCount = redisTemplate.keys(INDEX_KEY_PREFIX + "*") != null
                ? redisTemplate.keys(INDEX_KEY_PREFIX + "*").size()
                : 0;
        meta.put("locationCount", locationCount);

        return meta;
    }

    private void updateIndexMeta() {
        redisTemplate.opsForValue().set(INDEX_META_KEY + ":lastUpdate", Instant.now().toString());
    }

    @Scheduled(fixedRate = 300000)
    public void refreshIndexStatistics() {
        updateIndexMeta();
        log.debug("Refreshed index statistics");
    }

    public void clearAllIndexes() {
        Set<String> allKeys = redisTemplate.keys("loc_idx:*");
        if (allKeys != null && !allKeys.isEmpty()) {
            redisTemplate.delete(allKeys);
        }
    }
}
