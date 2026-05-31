package com.tracetopology.api.service;

import com.tracetopology.domain.topology.ServiceNode;
import com.tracetopology.domain.topology.ServiceTopology;
import com.tracetopology.domain.topology.TraceSpan;
import com.tracetopology.common.result.PageResult;

import java.util.List;
import java.util.Map;

public interface TopologyService {

    ServiceTopology buildTopology(List<TraceSpan> spans, String namespace);

    ServiceTopology getTopology(String namespace);

    ServiceNode registerNode(ServiceNode node);

    ServiceNode getNode(String nodeId);

    PageResult<ServiceNode> listNodes(String namespace, int pageNum, int pageSize);

    ServiceNode updateNode(String nodeId, Map<String, Object> updates);

    void deleteNode(String nodeId);

    void recordSpan(TraceSpan span);
}
