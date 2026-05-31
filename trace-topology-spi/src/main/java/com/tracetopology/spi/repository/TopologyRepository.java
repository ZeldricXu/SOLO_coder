package com.tracetopology.spi.repository;

import com.tracetopology.domain.topology.ServiceEdge;
import com.tracetopology.domain.topology.ServiceNode;
import com.tracetopology.domain.topology.ServiceTopology;
import com.tracetopology.domain.topology.TraceSpan;
import com.tracetopology.common.result.PageResult;

import java.util.List;
import java.util.Optional;

public interface TopologyRepository {

    void saveTopology(ServiceTopology topology);

    Optional<ServiceTopology> findTopologyByNamespace(String namespace);

    ServiceNode saveNode(ServiceNode node);

    Optional<ServiceNode> findNodeById(String nodeId);

    PageResult<ServiceNode> findNodesByNamespace(String namespace, int pageNum, int pageSize);

    void deleteNode(String nodeId);

    ServiceEdge saveEdge(ServiceEdge edge);

    Optional<ServiceEdge> findEdgeByServices(String sourceServiceId, String targetServiceId);

    List<ServiceEdge> findEdgesBySourceService(String sourceServiceId);

    void saveSpan(TraceSpan span);

    List<TraceSpan> findSpansByTraceId(String traceId);
}
