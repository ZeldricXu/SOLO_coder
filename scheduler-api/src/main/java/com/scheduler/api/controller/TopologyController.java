package com.scheduler.api.controller;

import com.scheduler.common.model.ApiResponse;
import com.scheduler.topology.model.TopologyGraph;
import com.scheduler.topology.service.TopologyBuilder;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/topology")
@RequiredArgsConstructor
public class TopologyController {

    private final TopologyBuilder topologyBuilder;

    @GetMapping
    public Mono<ResponseEntity<ApiResponse<TopologyGraph>>> getTopology(
            @RequestParam(defaultValue = "60") int lookbackMinutes) {
        return Mono.fromCallable(() -> {
            TopologyGraph graph = topologyBuilder.buildTopology(lookbackMinutes);
            return ResponseEntity.ok(ApiResponse.success(graph));
        });
    }

    @GetMapping("/services/{serviceName}/downstream")
    public Mono<ResponseEntity<ApiResponse<List<String>>>> getDownstreamServices(
            @PathVariable String serviceName,
            @RequestParam(defaultValue = "60") int lookbackMinutes) {
        return Mono.fromCallable(() -> {
            TopologyGraph graph = topologyBuilder.buildTopology(lookbackMinutes);
            List<String> downstream = topologyBuilder.getDownstreamServices(serviceName, graph);
            return ResponseEntity.ok(ApiResponse.success(downstream));
        });
    }

    @GetMapping("/services/{serviceName}/upstream")
    public Mono<ResponseEntity<ApiResponse<List<String>>>> getUpstreamServices(
            @PathVariable String serviceName,
            @RequestParam(defaultValue = "60") int lookbackMinutes) {
        return Mono.fromCallable(() -> {
            TopologyGraph graph = topologyBuilder.buildTopology(lookbackMinutes);
            List<String> upstream = topologyBuilder.getUpstreamServices(serviceName, graph);
            return ResponseEntity.ok(ApiResponse.success(upstream));
        });
    }
}
