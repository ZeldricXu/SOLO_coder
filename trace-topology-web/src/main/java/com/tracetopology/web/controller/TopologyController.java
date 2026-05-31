package com.tracetopology.web.controller;

import com.tracetopology.api.service.TopologyService;
import com.tracetopology.common.result.PageResult;
import com.tracetopology.common.result.Result;
import com.tracetopology.domain.topology.ServiceNode;
import com.tracetopology.domain.topology.ServiceTopology;
import com.tracetopology.domain.topology.TraceSpan;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/topology")
@RequiredArgsConstructor
public class TopologyController {

    private final TopologyService topologyService;

    @PostMapping("/build")
    public Mono<Result<ServiceTopology>> buildTopology(
            @RequestParam(required = false, defaultValue = "default") String namespace,
            @RequestBody List<Map<String, Object>> spans) {
        return Mono.fromCallable(() -> {
            log.info("构建服务拓扑: namespace={}, spans={}", namespace, spans.size());

            List<TraceSpan> traceSpans = spans.stream()
                    .map(this::mapToTraceSpan)
                    .toList();

            ServiceTopology topology = topologyService.buildTopology(traceSpans, namespace);
            return Result.success(topology);
        });
    }

    @GetMapping
    public Mono<Result<ServiceTopology>> getTopology(
            @RequestParam(required = false, defaultValue = "default") String namespace) {
        return Mono.fromCallable(() -> {
            ServiceTopology topology = topologyService.getTopology(namespace);
            return Result.success(topology);
        });
    }

    @PostMapping("/nodes")
    public Mono<Result<ServiceNode>> registerNode(@RequestBody ServiceNode node) {
        return Mono.fromCallable(() -> {
            log.info("注册服务节点: serviceName={}", node.getServiceName());
            ServiceNode registered = topologyService.registerNode(node);
            return Result.success(registered);
        });
    }

    @GetMapping("/nodes")
    public Mono<Result<PageResult<ServiceNode>>> listNodes(
            @RequestParam(required = false, defaultValue = "default") String namespace,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize) {
        return Mono.fromCallable(() -> {
            PageResult<ServiceNode> nodes = topologyService.listNodes(namespace, pageNum, pageSize);
            return Result.success(nodes);
        });
    }

    @GetMapping("/nodes/{id}")
    public Mono<Result<ServiceNode>> getNode(@PathVariable String id) {
        return Mono.fromCallable(() -> {
            ServiceNode node = topologyService.getNode(id);
            return Result.success(node);
        });
    }

    @PutMapping("/nodes/{id}")
    public Mono<Result<ServiceNode>> updateNode(
            @PathVariable String id,
            @RequestBody Map<String, Object> updates) {
        return Mono.fromCallable(() -> {
            ServiceNode node = topologyService.updateNode(id, updates);
            return Result.success(node);
        });
    }

    @DeleteMapping("/nodes/{id}")
    public Mono<Result<Void>> deleteNode(@PathVariable String id) {
        return Mono.fromCallable(() -> {
            topologyService.deleteNode(id);
            return Result.success();
        });
    }

    @PostMapping("/spans")
    public Mono<Result<Void>> recordSpan(@RequestBody Map<String, Object> spanData) {
        return Mono.fromCallable(() -> {
            TraceSpan span = mapToTraceSpan(spanData);
            topologyService.recordSpan(span);
            return Result.success();
        });
    }

    private TraceSpan mapToTraceSpan(Map<String, Object> spanData) {
        return TraceSpan.builder()
                .traceId((String) spanData.get("traceId"))
                .spanId((String) spanData.get("spanId"))
                .parentSpanId((String) spanData.get("parentSpanId"))
                .serviceName((String) spanData.get("serviceName"))
                .operationName((String) spanData.get("operationName"))
                .startTime(spanData.containsKey("startTime")
                        ? Instant.parse((String) spanData.get("startTime"))
                        : Instant.now())
                .durationMs(spanData.containsKey("durationMs")
                        ? ((Number) spanData.get("durationMs")).longValue()
                        : 0)
                .success((Boolean) spanData.getOrDefault("success", true))
                .tags(spanData.containsKey("tags")
                        ? (Map<String, Object>) spanData.get("tags")
                        : Map.of())
                .build();
    }
}
