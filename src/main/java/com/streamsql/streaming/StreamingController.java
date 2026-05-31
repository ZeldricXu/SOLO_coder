package com.streamsql.streaming;

import com.streamsql.common.ApiResponse;
import com.streamsql.feature.FeatureFlagService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/streaming")
@RequiredArgsConstructor
public class StreamingController {

    private final StreamingService streamingService;
    private final StreamingConfig streamingConfig;
    private final FeatureFlagService featureFlagService;

    @GetMapping("/config")
    public Mono<ApiResponse<StreamingConfig>> getConfig() {
        return Mono.just(ApiResponse.success(streamingConfig));
    }

    @PostMapping("/batch")
    public Mono<ApiResponse<Map<String, Object>>> testBatchProcessing(
            @RequestBody Map<String, Object> request,
            @RequestParam(defaultValue = "batch") String mode) {

        return featureFlagService.executeWithFeature(
                "streaming-processing",
                () -> {
                    @SuppressWarnings("unchecked")
                    List<Object> data = (List<Object>) request.getOrDefault("data", List.of());
                    int batchSize = (int) request.getOrDefault("batchSize", 100);

                    List<String> results = streamingService.processLargeDataset(
                            data,
                            batch -> "Processed " + batch.size() + " items",
                            mode
                    );

                    Map<String, Object> response = new HashMap<>();
                    response.put("mode", mode);
                    response.put("totalItems", data.size());
                    response.put("batchSize", batchSize);
                    response.put("results", results);

                    return ApiResponse.success(response);
                },
                () -> ApiResponse.error(400, "Streaming processing feature is disabled")
        );
    }

    @GetMapping("/stats")
    public Mono<ApiResponse<Map<String, Object>>> getStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("featureEnabled", featureFlagService.isEnabled("streaming-processing"));
        stats.put("defaultBatchSize", streamingConfig.getBatchSize());
        stats.put("parallelism", streamingConfig.getParallelism());
        stats.put("flushIntervalMs", streamingConfig.getFlushIntervalMs());
        stats.put("queueCapacity", streamingConfig.getQueueCapacity());
        return Mono.just(ApiResponse.success(stats));
    }
}
