package com.scheduler.topology.service;

import com.scheduler.persistence.entity.TraceSpan;
import com.scheduler.persistence.mapper.TraceSpanMapper;
import com.scheduler.topology.model.ServiceEdge;
import com.scheduler.topology.model.ServiceNode;
import com.scheduler.topology.model.TopologyGraph;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jgrapht.Graph;
import org.jgrapht.graph.DefaultDirectedGraph;
import org.jgrapht.graph.DefaultEdge;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class TopologyBuilder {

    private final TraceSpanMapper spanMapper;
    private final Map<String, ServiceNode> nodeCache = new ConcurrentHashMap<>();
    private final Map<String, ServiceEdge> edgeCache = new ConcurrentHashMap<>();

    public TopologyGraph buildTopology(int lookbackMinutes) {
        Instant start = Instant.now().minus(lookbackMinutes, ChronoUnit.MINUTES);
        List<String> services = spanMapper.findDistinctServices(start);

        List<ServiceNode> nodes = services.stream()
                .map(this::buildServiceNode)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        List<ServiceEdge> edges = buildEdges(start);

        long totalRequests = nodes.stream().mapToLong(ServiceNode::getRequestCount).sum();
        long totalErrors = nodes.stream().mapToLong(ServiceNode::getErrorCount).sum();

        Map<String, Object> summary = Map.of(
                "serviceCount", nodes.size(),
                "dependencyCount", edges.size(),
                "totalRequests", totalRequests,
                "errorRate", totalRequests > 0 ? (double) totalErrors / totalRequests : 0,
                "lookbackMinutes", lookbackMinutes
        );

        return TopologyGraph.builder()
                .nodes(nodes)
                .edges(edges)
                .summary(summary)
                .timestamp(System.currentTimeMillis())
                .build();
    }

    private ServiceNode buildServiceNode(String serviceName) {
        Instant end = Instant.now();
        Instant start = end.minus(1, ChronoUnit.HOURS);
        List<TraceSpan> spans = spanMapper.findByServiceNameAndTimeRange(serviceName, start, end);

        if (spans.isEmpty()) {
            return null;
        }

        long requestCount = spans.size();
        long errorCount = spans.stream()
                .filter(s -> "ERROR".equalsIgnoreCase(s.getStatus()))
                .count();

        List<Long> latencies = spans.stream()
                .filter(s -> s.getDurationMicros() != null)
                .map(s -> s.getDurationMicros() / 1000)
                .sorted()
                .collect(Collectors.toList());

        double avgLatency = latencies.stream().mapToLong(Long::longValue).average().orElse(0);
        double p99Latency = latencies.isEmpty() ? 0 : latencies.get((int) (latencies.size() * 0.99));

        Set<String> hosts = spans.stream()
                .map(TraceSpan::getHost)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        Instant lastSeen = spans.stream()
                .map(TraceSpan::getStartTime)
                .max(Instant::compareTo)
                .orElse(Instant.now());

        return ServiceNode.builder()
                .serviceName(serviceName)
                .instanceCount(hosts.size())
                .requestCount(requestCount)
                .errorCount(errorCount)
                .avgLatencyMs(avgLatency)
                .p99LatencyMs(p99Latency)
                .lastSeen(lastSeen)
                .status(errorCount > 0 ? "DEGRADED" : "HEALTHY")
                .build();
    }

    private List<ServiceEdge> buildEdges(Instant start) {
        Map<String, ServiceEdge> edges = new HashMap<>();
        List<String> services = spanMapper.findDistinctServices(start);

        for (String service : services) {
            List<TraceSpan> serviceSpans = spanMapper.findByServiceNameAndTimeRange(service, start, Instant.now());
            for (TraceSpan span : serviceSpans) {
                if (span.getParentSpanId() != null) {
                    String edgeKey = service + "->" + span.getServiceName();
                    ServiceEdge edge = edges.computeIfAbsent(edgeKey, k -> ServiceEdge.builder()
                            .sourceService(service)
                            .targetService(span.getServiceName())
                            .callCount(0)
                            .errorCount(0)
                            .build());
                    edge.setCallCount(edge.getCallCount() + 1);
                    if ("ERROR".equalsIgnoreCase(span.getStatus())) {
                        edge.setErrorCount(edge.getErrorCount() + 1);
                    }
                    if (span.getDurationMicros() != null) {
                        double latencyMs = span.getDurationMicros() / 1000.0;
                        edge.setAvgLatencyMs((edge.getAvgLatencyMs() * (edge.getCallCount() - 1) + latencyMs) / edge.getCallCount());
                    }
                    edge.setLastCallTime(span.getStartTime());
                }
            }
        }

        return new ArrayList<>(edges.values());
    }

    public Graph<String, DefaultEdge> buildJGraphT(TopologyGraph topology) {
        Graph<String, DefaultEdge> graph = new DefaultDirectedGraph<>(DefaultEdge.class);
        topology.getNodes().forEach(node -> graph.addVertex(node.getServiceName()));
        topology.getEdges().forEach(edge -> {
            if (graph.containsVertex(edge.getSourceService()) && graph.containsVertex(edge.getTargetService())) {
                graph.addEdge(edge.getSourceService(), edge.getTargetService());
            }
        });
        return graph;
    }

    public List<String> getDownstreamServices(String serviceName, TopologyGraph topology) {
        return topology.getEdges().stream()
                .filter(e -> e.getSourceService().equals(serviceName))
                .map(ServiceEdge::getTargetService)
                .distinct()
                .collect(Collectors.toList());
    }

    public List<String> getUpstreamServices(String serviceName, TopologyGraph topology) {
        return topology.getEdges().stream()
                .filter(e -> e.getTargetService().equals(serviceName))
                .map(ServiceEdge::getSourceService)
                .distinct()
                .collect(Collectors.toList());
    }
}
