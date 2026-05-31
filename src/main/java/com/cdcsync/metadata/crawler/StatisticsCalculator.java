package com.cdcsync.metadata.crawler;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.TypeReference;
import com.cdcsync.common.exception.BusinessException;
import com.cdcsync.common.util.ValidationUtils;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
class StatisticsCalculator {

    private static final int MAX_SAMPLE_VALUES = 10;
    private static final int MAX_STRING_LENGTH = 65536;

    Map<String, Object> calculateColumnStatistics(List<Map<String, Object>> sampleData, String columnsJson) {
        ValidationUtils.notBlank(columnsJson, "columnsJson");

        Map<String, Object> statistics = new LinkedHashMap<>();
        List<Map<String, Object>> columns;

        try {
            columns = JSON.parseObject(columnsJson, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            log.error("Failed to parse columns JSON: {}", e.getMessage());
            throw new BusinessException("Failed to parse columns metadata: " + e.getMessage(), e);
        }

        if (columns == null || columns.isEmpty()) {
            statistics.put("rowCount", sampleData != null ? sampleData.size() : 0);
            statistics.put("columnStats", Collections.emptyMap());
            statistics.put("warning", "No column metadata available");
            return statistics;
        }

        if (sampleData == null || sampleData.isEmpty()) {
            statistics.put("rowCount", 0);
            statistics.put("columnStats", Collections.emptyMap());
            return statistics;
        }

        statistics.put("rowCount", sampleData.size());
        Map<String, Object> columnStats = new LinkedHashMap<>();

        for (Map<String, Object> column : columns) {
            Object nameObj = column.get("name");
            if (!(nameObj instanceof String columnName)) {
                log.warn("Skipping column with invalid name: {}", nameObj);
                continue;
            }

            try {
                Map<String, Object> stats = analyzeColumn(sampleData, columnName);
                columnStats.put(columnName, stats);
            } catch (Exception e) {
                log.warn("Failed to analyze column {}: {}", columnName, e.getMessage());
                Map<String, Object> errorStats = new LinkedHashMap<>();
                errorStats.put("error", e.getMessage());
                columnStats.put(columnName, errorStats);
            }
        }

        statistics.put("columnStats", columnStats);
        return statistics;
    }

    private Map<String, Object> analyzeColumn(List<Map<String, Object>> sampleData, String columnName) {
        ValidationUtils.notBlank(columnName, "columnName");
        ValidationUtils.notEmpty(sampleData, "sampleData");

        Map<String, Object> stats = new LinkedHashMap<>();
        List<Object> values = new ArrayList<>();
        int nullCount = 0;
        int totalRows = sampleData.size();

        for (Map<String, Object> row : sampleData) {
            if (row == null) {
                nullCount++;
                continue;
            }

            Object value = row.get(columnName);
            if (value == null) {
                nullCount++;
            } else {
                values.add(value);
            }
        }

        stats.put("nullCount", nullCount);
        stats.put("nonNullCount", values.size());
        stats.put("nullRatio", totalRows == 0 ? 0 : (double) nullCount / totalRows);

        if (!values.isEmpty()) {
            try {
                analyzeColumnValues(stats, values);
            } catch (Exception e) {
                log.warn("Failed to analyze values for column {}: {}", columnName, e.getMessage());
                stats.put("analysisError", e.getMessage());
            }

            try {
                Set<Object> uniqueValues = new HashSet<>(values);
                stats.put("cardinality", uniqueValues.size());
                stats.put("uniqueRatio", (double) uniqueValues.size() / values.size());

                int sampleCount = Math.min(MAX_SAMPLE_VALUES, values.size());
                stats.put("sampleValues", new ArrayList<>(values.subList(0, sampleCount)));
            } catch (Exception e) {
                log.warn("Failed to calculate cardinality for column {}: {}", columnName, e.getMessage());
            }
        }

        return stats;
    }

    private void analyzeColumnValues(Map<String, Object> stats, List<Object> values) {
        if (values == null || values.isEmpty()) {
            return;
        }

        Object firstValue = values.get(0);

        if (firstValue instanceof Number) {
            addNumericStats(stats, values);
        } else if (firstValue instanceof String) {
            addStringStats(stats, values);
        } else if (firstValue instanceof Boolean) {
            addBooleanStats(stats, values);
        } else if (firstValue instanceof java.util.Date || firstValue instanceof java.time.temporal.Temporal) {
            addDateStats(stats, values);
        } else if (firstValue instanceof byte[]) {
            stats.put("dataType", "binary");
            stats.put("length", ((byte[]) firstValue).length);
        } else {
            stats.put("dataType", firstValue != null ? firstValue.getClass().getSimpleName() : "unknown");
        }
    }

    private void addNumericStats(Map<String, Object> stats, List<Object> values) {
        double sum = 0;
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        int validCount = 0;

        for (Object value : values) {
            if (value instanceof Number num) {
                try {
                    double d = num.doubleValue();
                    if (!Double.isNaN(d) && !Double.isInfinite(d)) {
                        sum += d;
                        min = Math.min(min, d);
                        max = Math.max(max, d);
                        validCount++;
                    }
                } catch (Exception e) {
                    log.debug("Skipping invalid numeric value: {}", value);
                }
            }
        }

        if (validCount > 0) {
            stats.put("min", min);
            stats.put("max", max);
            stats.put("avg", sum / validCount);
            stats.put("validCount", validCount);
        } else {
            stats.put("min", null);
            stats.put("max", null);
            stats.put("avg", null);
            stats.put("warning", "No valid numeric values");
        }
        stats.put("dataType", "numeric");
    }

    private void addStringStats(Map<String, Object> stats, List<Object> values) {
        int minLength = Integer.MAX_VALUE;
        int maxLength = 0;
        long totalLength = 0;
        int validCount = 0;
        int truncatedCount = 0;

        for (Object value : values) {
            if (value instanceof String str) {
                int len = str.length();
                if (len > MAX_STRING_LENGTH) {
                    truncatedCount++;
                }
                minLength = Math.min(minLength, len);
                maxLength = Math.max(maxLength, len);
                totalLength += Math.min(len, MAX_STRING_LENGTH);
                validCount++;
            }
        }

        if (validCount > 0) {
            stats.put("minLength", minLength);
            stats.put("maxLength", maxLength);
            stats.put("avgLength", (double) totalLength / validCount);
            stats.put("validCount", validCount);
            if (truncatedCount > 0) {
                stats.put("truncatedCount", truncatedCount);
            }
        } else {
            stats.put("minLength", 0);
            stats.put("maxLength", 0);
            stats.put("avgLength", 0.0);
        }
        stats.put("dataType", "string");
    }

    private void addBooleanStats(Map<String, Object> stats, List<Object> values) {
        int trueCount = 0;
        int falseCount = 0;

        for (Object value : values) {
            if (Boolean.TRUE.equals(value)) {
                trueCount++;
            } else if (Boolean.FALSE.equals(value)) {
                falseCount++;
            }
        }

        stats.put("trueCount", trueCount);
        stats.put("falseCount", falseCount);
        stats.put("trueRatio", (double) trueCount / values.size());
        stats.put("dataType", "boolean");
    }

    private void addDateStats(Map<String, Object> stats, List<Object> values) {
        stats.put("dataType", "date");
        if (!values.isEmpty()) {
            try {
                List<String> strValues = values.stream()
                        .filter(Objects::nonNull)
                        .map(Object::toString)
                        .filter(s -> !s.isEmpty())
                        .collect(Collectors.toList());

                if (!strValues.isEmpty()) {
                    stats.put("earliest", Collections.min(strValues));
                    stats.put("latest", Collections.max(strValues));
                }
            } catch (Exception e) {
                log.warn("Failed to calculate date range: {}", e.getMessage());
                stats.put("warning", "Failed to calculate date range");
            }
        }
    }

    String calculateStatisticsJson(List<Map<String, Object>> sampleData, String columnsJson) {
        return JSON.toJSONString(calculateColumnStatistics(sampleData, columnsJson));
    }

    Map<String, Object> buildTableStatistics(long rowCount, long sizeBytes, String statisticsJson) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rowCount", Math.max(0, rowCount));
        result.put("sizeBytes", Math.max(0, sizeBytes));
        result.put("sizeKB", sizeBytes / 1024.0);
        result.put("sizeMB", sizeBytes / (1024.0 * 1024.0));

        if (statisticsJson != null && !statisticsJson.trim().isEmpty()) {
            try {
                result.put("statistics", JSON.parseObject(statisticsJson));
            } catch (Exception e) {
                log.warn("Failed to parse statistics JSON: {}", e.getMessage());
                result.put("statistics", Collections.emptyMap());
                result.put("statisticsParseError", e.getMessage());
            }
        } else {
            result.put("statistics", Collections.emptyMap());
        }

        return result;
    }
}
