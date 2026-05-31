package com.streamsql.streaming;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

@Slf4j
@Service
@RequiredArgsConstructor
public class StreamingService {

    private final BatchProcessor batchProcessor;
    private final StreamingConfig config;

    public <T, R> List<R> processLargeDataset(List<T> data, Function<List<T>, R> processor, String processingMode) {
        return switch (processingMode) {
            case "batch" -> processBatchMode(data, processor);
            case "streaming" -> processStreamingMode(data, processor);
            case "micro_batch" -> processMicroBatchMode(data, processor);
            default -> processBatchMode(data, processor);
        };
    }

    private <T, R> List<R> processBatchMode(List<T> data, Function<List<T>, R> processor) {
        log.info("Processing {} records in BATCH mode", data.size());
        return batchProcessor.processWithRetry(data, processor, config.getBatchSize());
    }

    private <T, R> List<R> processStreamingMode(List<T> data, Function<List<T>, R> processor) {
        log.info("Processing {} records in STREAMING mode", data.size());
        return batchProcessor.processBatch(data, processor, config.getBatchSize());
    }

    private <T, R> List<R> processMicroBatchMode(List<T> data, Function<List<T>, R> processor) {
        int microBatchSize = Math.max(1, config.getBatchSize() / 10);
        log.info("Processing {} records in MICRO_BATCH mode with batch size {}", data.size(), microBatchSize);
        return batchProcessor.processBatch(data, processor, microBatchSize);
    }

    public <T, R> CompletableFuture<List<R>> processAsync(List<T> data, Function<List<T>, R> processor, int batchSize) {
        return CompletableFuture.supplyAsync(() ->
                batchProcessor.processBatch(data, processor, batchSize)
        );
    }

    public <T, R> List<R> processParallel(List<T> data, Function<T, R> processor) {
        log.info("Processing {} records in parallel with {} threads", data.size(), config.getParallelism());
        return batchProcessor.processParallel(data, processor);
    }
}
