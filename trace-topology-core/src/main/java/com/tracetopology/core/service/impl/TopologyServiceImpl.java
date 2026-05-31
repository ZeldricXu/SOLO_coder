package com.tracetopology.core.service.impl;

import com.tracetopology.api.service.TopologyService;
import com.tracetopology.common.exception.BaseException;
import com.tracetopology.common.result.PageResult;
import com.tracetopology.common.utils.IdGenerator;
import com.tracetopology.core.validation.ParamValidator;
import com.tracetopology.domain.topology.ServiceEdge;
import com.tracetopology.domain.topology.ServiceNode;
import com.tracetopology.domain.topology.ServiceTopology;
import com.tracetopology.domain.topology.TraceSpan;
import com.tracetopology.spi.repository.TopologyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@RequiredArgsConstructor
public class TopologyServiceImpl implements TopologyService {

    private final TopologyRepository topologyRepository;

    @Override
    public ServiceTopology buildTopology(List<TraceSpan> spans, String namespace) {
        ParamValidator.validateNotNull(spans, "spans");
        ParamValidator.validateNotBlank(namespace, "namespace");

        ServiceTopology topology = ServiceTopology.builder()
                .id(IdGenerator.generateId("topo"))
                .namespace(namespace)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        Map<String, ServiceNode> nodeMap = new HashMap<>();
        Map<String, ServiceEdge> edgeMap = new HashMap<>();

        for (TraceSpan span : spans) {
            ServiceNode node = getOrCreateNode(span, nodeMap, namespace);
            topology.addNode(node);

            if (span.getParentSpanId() != null) {
                Optional<TraceSpan> parentSpan = spans.stream()
                        .filter(s -> s.getSpanId().equals(span.getParentSpanId()))
                        .findFirst();

                if (parentSpan.isPresent()) {
                    TraceSpan parent = parentSpan.get();
                    ServiceEdge edge = getOrCreateEdge(parent, span, edgeMap);
                    edge.recordCall(span.getDurationMs(), span.isSuccess());
                    topology.addEdge(edge);
                }
            }
        }

        topologyRepository.saveTopology(topology);
        return topology;
    }

    private ServiceNode getOrCreateNode(TraceSpan span, Map<String, ServiceNode> nodeMap, String namespace) {
        String serviceName = span.getServiceName();
        if (!nodeMap.containsKey(serviceName)) {
            ServiceNode node = ServiceNode.builder()
                    .id(IdGenerator.generateId("node"))
                    .serviceName(serviceName)
                    .serviceType("service")
                    .namespace(namespace)
                    .version("1.0")
                    .metadata(new HashMap<>())
                    .registeredAt(Instant.now())
                    .lastHeartbeatAt(Instant.now())
                    .active(true)
                    .build();
            nodeMap.put(serviceName, node);
        }
        return nodeMap.get(serviceName);
    }

    private ServiceEdge getOrCreateEdge(TraceSpan parent, TraceSpan current, Map<String, ServiceEdge> edgeMap) {
        String edgeKey = parent.getServiceName() + "->" + current.getServiceName();
        if (!edgeMap.containsKey(edgeKey)) {
            ServiceEdge edge = ServiceEdge.builder()
                    .id(IdGenerator.generateId("edge"))
                    .sourceServiceName(parent.getServiceName())
                    .targetServiceName(current.getServiceName())
                    .callCount(0)
                    .errorCount(0)
                    .avgLatencyMs(0)
                    .p99LatencyMs(0)
                    .firstCallAt(Instant.now())
                    .lastCallAt(Instant.now())
                    .build();
            edgeMap.put(edgeKey, edge);
        }
        return edgeMap.get(edgeKey);
    }

    @Override
    public ServiceTopology getTopology(String namespace) {
        ParamValidator.validateNotBlank(namespace, "namespace");
        return topologyRepository.findTopologyByNamespace(namespace)
                .orElseThrow(() -> new BaseException("TOPOLOGY_NOT_FOUND", "拓扑不存在: " + namespace));
    }

    @Override
    public ServiceNode registerNode(ServiceNode node) {
        ParamValidator.validateNotNull(node, "node");
        ParamValidator.validateNotBlank(node.getServiceName(), "node.serviceName");

        if (node.getId() == null) {
            node.setId(IdGenerator.generateId("node"));
        }
        if (node.getRegisteredAt() == null) {
            node.setRegisteredAt(Instant.now());
        }
        node.setLastHeartbeatAt(Instant.now());
        node.setActive(true);

        return topologyRepository.saveNode(node);
    }

    @Override
    public ServiceNode getNode(String nodeId) {
        ParamValidator.validateNotBlank(nodeId, "nodeId");
        return topologyRepository.findNodeById(nodeId)
                .orElseThrow(() -> new BaseException("NODE_NOT_FOUND", "节点不存在: " + nodeId));
    }

    @Override
    public PageResult<ServiceNode> listNodes(String namespace, int pageNum, int pageSize) {
        ParamValidator.validateNotBlank(namespace, "namespace");
        ParamValidator.validatePositive(pageNum, "pageNum");
        ParamValidator.validatePositive(pageSize, "pageSize");
        return topologyRepository.findNodesByNamespace(namespace, pageNum, pageSize);
    }

    @Override
    public ServiceNode updateNode(String nodeId, Map<String, Object> updates) {
        ParamValidator.validateNotBlank(nodeId, "nodeId");
        ServiceNode node = getNode(nodeId);

        if (updates.containsKey("version")) {
            node.setVersion((String) updates.get("version"));
        }
        if (updates.containsKey("metadata")) {
            @SuppressWarnings("unchecked")
            Map<String, String> metadata = (Map<String, String>) updates.get("metadata");
            node.setMetadata(metadata);
        }
        if (updates.containsKey("active")) {
            node.setActive((Boolean) updates.get("active"));
        }

        return topologyRepository.saveNode(node);
    }

    @Override
    public void deleteNode(String nodeId) {
        ParamValidator.validateNotBlank(nodeId, "nodeId");
        topologyRepository.deleteNode(nodeId);
    }

    @Override
    public void recordSpan(TraceSpan span) {
        ParamValidator.validateNotNull(span, "span");
        ParamValidator.validateNotBlank(span.getTraceId(), "span.traceId");
        ParamValidator.validateNotBlank(span.getSpanId(), "span.spanId");
        ParamValidator.validateNotBlank(span.getServiceName(), "span.serviceName");

        topologyRepository.saveSpan(span);
    }

    public List<Map<String, Object>> analyzeTopology(ServiceTopology topology) {
        List<Map<String, Object>> analysis = new ArrayList<>();

        Map<String, Long> inboundCalls = topology.getEdges().stream()
                .collect(Collectors.groupingBy(ServiceEdge::getTargetServiceName,
                        Collectors.summingLong(ServiceEdge::getCallCount)));

        Map<String, Long> outboundCalls = topology.getEdges().stream()
                .collect(Collectors.groupingBy(ServiceEdge::getSourceServiceName,
                        Collectors.summingLong(ServiceEdge::getCallCount)));

        for (ServiceNode node : topology.getNodes().values()) {
            Map<String, Object> nodeAnalysis = new HashMap<>();
            nodeAnalysis.put("serviceName", node.getServiceName());
            nodeAnalysis.put("inboundCalls", inboundCalls.getOrDefault(node.getServiceName(), 0L));
            nodeAnalysis.put("outboundCalls", outboundCalls.getOrDefault(node.getServiceName(), 0L));
            nodeAnalysis.put("active", node.isActive());
            analysis.add(nodeAnalysis);
        }

        return analysis;
    }
}
