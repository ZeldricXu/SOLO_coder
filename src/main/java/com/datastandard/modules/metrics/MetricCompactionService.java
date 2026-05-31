package com.datastandard.modules.metrics;

import com.datastandard.modules.metrics.dto.AggregateQuery;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class MetricCompactionService {

    private final RedisStorageAdapter redisStorageAdapter;
    private final MySqlStorageAdapter mySqlStorageAdapter;

    private static final long RAW_DATA_RETENTION_HOURS = 72;
    private static final long MINUTE_DATA_RETENTION_DAYS = 7;
    private static final long HOUR_DATA_RETENTION_DAYS = 30;
    private static final long DAY_DATA_RETENTION_DAYS = 365;

    @Scheduled(cron = "0 30 2 * * *")
    public void runCompaction() {
        log.info("Starting metric compaction process");
        compactRawData()
                .then(compactMinuteData())
                .then(compactHourData())
                .then(compactDayData())
                .then(compactRedisHotData())
                .subscribe(
                        null,
                        error -> log.error("Compaction process failed", error),
                        () -> log.info("Metric compaction process completed successfully")
                );
    }

    public Mono<Boolean> compactRawData() {
        Instant cutoffTime = Instant.now().minus(RAW_DATA_RETENTION_HOURS, ChronoUnit.HOURS);
        log.info("Compacting raw data older than {}", cutoffTime);

        return mySqlStorageAdapter.deleteOldData(cutoffTime, AggregateQuery.AggregateLevel.RAW)
                .doOnSuccess(success -> {
                    if (success) {
                        log.info("Raw data compaction completed successfully");
                    } else {
                        log.warn("Raw data compaction may have encountered issues");
                    }
                });
    }

    public Mono<Boolean> compactMinuteData() {
        Instant cutoffTime = Instant.now().minus(MINUTE_DATA_RETENTION_DAYS, ChronoUnit.DAYS);
        log.info("Compacting minute-level data older than {}", cutoffTime);

        return mySqlStorageAdapter.deleteOldData(cutoffTime, AggregateQuery.AggregateLevel.MINUTE)
                .doOnSuccess(success -> {
                    if (success) {
                        log.info("Minute-level data compaction completed successfully");
                    } else {
                        log.warn("Minute-level data compaction may have encountered issues");
                    }
                });
    }

    public Mono<Boolean> compactHourData() {
        Instant cutoffTime = Instant.now().minus(HOUR_DATA_RETENTION_DAYS, ChronoUnit.DAYS);
        log.info("Compacting hour-level data older than {}", cutoffTime);

        return mySqlStorageAdapter.deleteOldData(cutoffTime, AggregateQuery.AggregateLevel.HOUR)
                .doOnSuccess(success -> {
                    if (success) {
                        log.info("Hour-level data compaction completed successfully");
                    } else {
                        log.warn("Hour-level data compaction may have encountered issues");
                    }
                });
    }

    public Mono<Boolean> compactDayData() {
        Instant cutoffTime = Instant.now().minus(DAY_DATA_RETENTION_DAYS, ChronoUnit.DAYS);
        log.info("Compacting day-level data older than {}", cutoffTime);

        return mySqlStorageAdapter.deleteOldData(cutoffTime, AggregateQuery.AggregateLevel.DAY)
                .doOnSuccess(success -> {
                    if (success) {
                        log.info("Day-level data compaction completed successfully");
                    } else {
                        log.warn("Day-level data compaction may have encountered issues");
                    }
                });
    }

    public Mono<Boolean> compactRedisHotData() {
        Instant cutoffTime = Instant.now().minus(24, ChronoUnit.HOURS);
        log.info("Compacting Redis hot data older than {}", cutoffTime);

        return redisStorageAdapter.deleteOldData(cutoffTime, AggregateQuery.AggregateLevel.RAW)
                .doOnSuccess(success -> {
                    if (success) {
                        log.info("Redis hot data compaction completed successfully");
                    } else {
                        log.warn("Redis hot data compaction may have encountered issues");
                    }
                });
    }

    public Mono<CompactionStats> getCompactionStats() {
        return Mono.zip(
                mySqlStorageAdapter.count("*", Instant.now().minus(365, ChronoUnit.DAYS), Instant.now()),
                redisStorageAdapter.count("*", Instant.now().minus(24, ChronoUnit.HOURS), Instant.now())
        ).map(tuple -> CompactionStats.builder()
                .mysqlDataPoints(tuple.getT1())
                .redisDataPoints(tuple.getT2())
                .rawDataRetentionHours(RAW_DATA_RETENTION_HOURS)
                .minuteDataRetentionDays(MINUTE_DATA_RETENTION_DAYS)
                .hourDataRetentionDays(HOUR_DATA_RETENTION_DAYS)
                .dayDataRetentionDays(DAY_DATA_RETENTION_DAYS)
                .build());
    }

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class CompactionStats {
        private long mysqlDataPoints;
        private long redisDataPoints;
        private long rawDataRetentionHours;
        private long minuteDataRetentionDays;
        private long hourDataRetentionDays;
        private long dayDataRetentionDays;
    }
}
