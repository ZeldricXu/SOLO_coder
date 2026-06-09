package com.loganalytics.storage.query;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.loganalytics.common.model.LogEvent;
import com.loganalytics.storage.config.StorageConfig;
import com.loganalytics.storage.minio.MinioArchiveManager;
import com.loganalytics.storage.postgres.MetadataIndexManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class QueryCoordinator {
    private static final Logger log = LoggerFactory.getLogger(QueryCoordinator.class);

    private final StorageConfig config;
    private final MinioArchiveManager minioManager;
    private final MetadataIndexManager indexManager;
    private final ExecutorService queryExecutor;
    private final Cache<String, Object> queryCache;

    public QueryCoordinator(StorageConfig config,
                            MinioArchiveManager minioManager,
                            MetadataIndexManager indexManager) {
        this.config = config;
        this.minioManager = minioManager;
        this.indexManager = indexManager;
        this.queryExecutor = Executors.newFixedThreadPool(10);
        this.queryCache = Caffeine.newBuilder()
                .maximumSize(1000)
                .expireAfterWrite(Duration.ofMinutes(1))
                .build();
    }

    public static class QueryResult {
        private final List<LogEvent> events;
        private final long totalCount;
        private final long tookMs;
        private final boolean fromMinio;
        private final String scrollId;

        public QueryResult(List<LogEvent> events, long totalCount, long tookMs, boolean fromMinio, String scrollId) {
            this.events = events;
            this.totalCount = totalCount;
            this.tookMs = tookMs;
            this.fromMinio = fromMinio;
            this.scrollId = scrollId;
        }

        public List<LogEvent> getEvents() { return events; }
        public long getTotalCount() { return totalCount; }
        public long getTookMs() { return tookMs; }
        public boolean isFromMinio() { return fromMinio; }
        public String getScrollId() { return scrollId; }
    }

    public QueryResult search(Map<String, Object> filters, int limit, int offset) {
        long startTime = System.currentTimeMillis();
        String cacheKey = "search:" + filters.hashCode() + ":" + limit + ":" + offset;

        try {
            @SuppressWarnings("unchecked")
            QueryResult cached = (QueryResult) queryCache.getIfPresent(cacheKey);
            if (cached != null) {
                return cached;
            }

            long totalCount = indexManager.count(filters);

            List<MetadataIndexManager.LogSearchResult> indexResults =
                    indexManager.search(filters, limit, offset);

            List<LogEvent> events = new ArrayList<>();
            boolean fromMinio = false;

            for (MetadataIndexManager.LogSearchResult result : indexResults) {
                LogEvent event = result.getEvent();
                if (event.getMessage() != null && !event.getMessage().isBlank()) {
                    events.add(event);
                } else if (result.getMinioLocation() != null && minioManager != null) {
                    try {
                        List<LogEvent> minioEvents = minioManager.readRange(
                                result.getMinioLocation(),
                                result.getMinioOffset(),
                                result.getMinioLength(),
                                1
                        );
                        if (!minioEvents.isEmpty()) {
                            events.add(minioEvents.get(0));
                            fromMinio = true;
                        } else {
                            events.add(event);
                        }
                    } catch (Exception e) {
                        log.warn("Failed to read from MinIO, using index data: {}", e.getMessage());
                        events.add(event);
                    }
                } else {
                    events.add(event);
                }
            }

            long tookMs = System.currentTimeMillis() - startTime;
            QueryResult result = new QueryResult(events, totalCount, tookMs, fromMinio, null);
            queryCache.put(cacheKey, result);

            if (tookMs > 1000) {
                log.warn("Slow query: {} results, took {}ms, filters={}", events.size(), tookMs, filters);
            }

            return result;

        } catch (Exception e) {
            log.error("Query failed: {}", e.getMessage(), e);
            return new QueryResult(Collections.emptyList(), 0,
                    System.currentTimeMillis() - startTime, false, null);
        }
    }

    public CompletableFuture<QueryResult> searchAsync(Map<String, Object> filters, int limit, int offset) {
        return CompletableFuture.supplyAsync(
                () -> search(filters, limit, offset),
                queryExecutor
        );
    }

    public QueryResult getByTraceId(String traceId, int limit) {
        long startTime = System.currentTimeMillis();

        List<MetadataIndexManager.LogSearchResult> results = indexManager.getByTraceId(traceId, limit);
        List<LogEvent> events = new ArrayList<>();

        for (MetadataIndexManager.LogSearchResult result : results) {
            events.add(result.getEvent());
        }

        events.sort(Comparator.comparing(LogEvent::getTimestamp,
                Comparator.nullsLast(Comparator.naturalOrder())));

        return new QueryResult(events, events.size(),
                System.currentTimeMillis() - startTime, false, null);
    }

    public QueryResult getByServiceAndTime(
            String serviceName,
            Instant timeStart,
            Instant timeEnd,
            List<String> levels,
            String patternId,
            int limit,
            int offset) {

        Map<String, Object> filters = new LinkedHashMap<>();
        filters.put("serviceName", serviceName);
        filters.put("timeStart", timeStart);
        filters.put("timeEnd", timeEnd);

        if (levels != null && !levels.isEmpty()) {
            filters.put("level", levels);
        }
        if (patternId != null && !patternId.isBlank()) {
            filters.put("patternId", patternId);
        }

        return search(filters, limit, offset);
    }

    public QueryResult fulltextSearch(String query, Instant timeStart, Instant timeEnd,
                                      String serviceName, int limit, int offset) {
        Map<String, Object> filters = new LinkedHashMap<>();

        if (timeStart != null) filters.put("timeStart", timeStart);
        if (timeEnd != null) filters.put("timeEnd", timeEnd);
        if (serviceName != null && !serviceName.isBlank()) filters.put("serviceName", serviceName);

        if (config.isEnableFullTextSearch()) {
            filters.put("fulltext", query);
        } else {
            filters.put("messageContains", query);
        }

        return search(filters, limit, offset);
    }

    public Map<String, Object> getAggregations(Map<String, Object> filters) {
        Map<String, Object> aggs = new LinkedHashMap<>();

        long total = indexManager.count(filters);
        aggs.put("total", total);

        Map<String, Object> levelFilters = new LinkedHashMap<>(filters);
        long errorCount = indexManager.count(addFilter(levelFilters, "level", List.of("ERROR", "FATAL")));
        long warnCount = indexManager.count(addFilter(levelFilters, "level", "WARN"));
        long infoCount = indexManager.count(addFilter(levelFilters, "level", "INFO"));

        aggs.put("byLevel", Map.of(
                "ERROR", errorCount,
                "WARN", warnCount,
                "INFO", infoCount,
                "OTHER", total - errorCount - warnCount - infoCount
        ));

        if (filters.containsKey("timeStart") && filters.containsKey("timeEnd")) {
            Instant start = (Instant) filters.get("timeStart");
            Instant end = (Instant) filters.get("timeEnd");
            long durationSec = Math.max(1, Duration.between(start, end).getSeconds());
            aggs.put("eps", (double) total / durationSec);
        }

        return aggs;
    }

    private Map<String, Object> addFilter(Map<String, Object> filters, String key, Object value) {
        Map<String, Object> newFilters = new LinkedHashMap<>(filters);
        newFilters.put(key, value);
        return newFilters;
    }

    public List<LogEvent> getFullLogContent(MetadataIndexManager.LogSearchResult searchResult) throws Exception {
        if (searchResult.getMinioLocation() == null || minioManager == null) {
            return Collections.singletonList(searchResult.getEvent());
        }

        return minioManager.readRange(
                searchResult.getMinioLocation(),
                searchResult.getMinioOffset(),
                searchResult.getMinioLength(),
                1
        );
    }

    public void invalidateCache() {
        queryCache.invalidateAll();
        log.info("Query cache invalidated");
    }

    public Map<String, Object> getDiagnostics() {
        return Map.of(
                "cacheSize", queryCache.estimatedSize(),
                "minioEnabled", minioManager != null,
                "postgresEnabled", indexManager != null,
                "fullTextEnabled", config.isEnableFullTextSearch()
        );
    }

    public void shutdown() {
        queryExecutor.shutdown();
        try {
            if (!queryExecutor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)) {
                queryExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            queryExecutor.shutdownNow();
        }
    }
}
