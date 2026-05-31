package com.datastandard.modules.metrics;

import com.datastandard.modules.metrics.dto.MetricIngestRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
@RequiredArgsConstructor
public class MetricIngestionService {

    private final RedisStorageAdapter redisStorageAdapter;
    private final MySqlStorageAdapter mySqlStorageAdapter;

    private static final int BATCH_SIZE = 1000;
    private static final long BATCH_TIMEOUT_MS = 5000;

    private final Queue<Map<String, Object>> writeBuffer = new ConcurrentLinkedQueue<>();
    private final AtomicInteger bufferCount = new AtomicInteger(0);
    private volatile long lastFlushTime = System.currentTimeMillis();

    public Mono<Boolean> ingest(MetricIngestRequest request) {
        Instant timestamp = request.getTimestamp() != null ? request.getTimestamp() : Instant.now();

        Map<String, Object> metricData = Map.of(
                "metricName", request.getMetricName(),
                "value", request.getValue(),
                "dimensions", request.getDimensions() != null ? request.getDimensions() : Map.of(),
                "timestamp", timestamp
        );

        return redisStorageAdapter.write(
                request.getMetricName(),
                request.getValue(),
                request.getDimensions() != null ? request.getDimensions() : Map.of(),
                timestamp
        ).flatMap(redisSuccess -> {
            bufferWrite(metricData);
            return Mono.just(redisSuccess);
        });
    }

    public Mono<Integer> batchIngest(List<MetricIngestRequest> requests) {
        List<Mono<Boolean>> operations = new ArrayList<>();
        for (MetricIngestRequest request : requests) {
            operations.add(ingest(request));
        }

        return Flux.merge(operations)
                .map(success -> success ? 1 : 0)
                .reduce(Integer::sum);
    }

    private void bufferWrite(Map<String, Object> metricData) {
        writeBuffer.offer(metricData);
        int count = bufferCount.incrementAndGet();

        long currentTime = System.currentTimeMillis();
        if (count >= BATCH_SIZE || (currentTime - lastFlushTime) >= BATCH_TIMEOUT_MS) {
            flushBuffer();
        }
    }

    private synchronized void flushBuffer() {
        if (writeBuffer.isEmpty()) {
            return;
        }

        List<Map<String, Object>> batch = new ArrayList<>();
        Map<String, Object> item;
        while ((item = writeBuffer.poll()) != null && batch.size() < BATCH_SIZE) {
            batch.add(item);
        }

        if (!batch.isEmpty()) {
            int flushedCount = batch.size();
            bufferCount.addAndGet(-flushedCount);
            lastFlushTime = System.currentTimeMillis();

            mySqlStorageAdapter.batchWrite(batch)
                    .subscribe(
                            success -> log.debug("Flushed {} metrics to MySQL storage", flushedCount),
                            error -> log.error("Failed to flush metrics to MySQL", error)
                    );
        }
    }

    public Mono<Void> manualFlush() {
        return Mono.fromRunnable(this::flushBuffer);
    }

    public int getBufferSize() {
        return bufferCount.get();
    }
}
