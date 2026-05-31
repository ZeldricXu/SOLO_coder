package com.tracetopology.infrastructure.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tracetopology.common.exception.TopologyConsistencyException;
import com.tracetopology.common.result.PageResult;
import com.tracetopology.domain.topology.ServiceEdge;
import com.tracetopology.domain.topology.ServiceNode;
import com.tracetopology.domain.topology.ServiceTopology;
import com.tracetopology.domain.topology.TraceSpan;
import com.tracetopology.infrastructure.persistence.entity.ServiceNodePO;
import com.tracetopology.infrastructure.persistence.mapper.ServiceNodeMapper;
import com.tracetopology.spi.repository.TopologyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

@Slf4j
@Repository
@RequiredArgsConstructor
public class TopologyRepositoryImpl implements TopologyRepository {

    private final ServiceNodeMapper serviceNodeMapper;
    private final Map<String, ServiceTopology> topologyCache = new ConcurrentHashMap<>();
    private final Map<String, ServiceEdge> edgeCache = new ConcurrentHashMap<>();
    private final Map<String, List<TraceSpan>> spanCache = new ConcurrentHashMap<>();
    private final Map<String, Long> topologyVersion = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> inFlightWrites = new ConcurrentHashMap<>();

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveTopology(ServiceTopology topology) {
        String namespace = topology.getNamespace();
        log.debug("开始保存拓扑: namespace={}, nodes={}, edges={}",
                namespace, topology.getNodes().size(), topology.getEdges().size());

        long version = topologyVersion.getOrDefault(namespace, 0L);
        AtomicLong writeCounter = inFlightWrites.computeIfAbsent(namespace, k -> new AtomicLong(0));
        writeCounter.incrementAndGet();

        try {
            List<ServiceNode> nodesSnapshot = new ArrayList<>(topology.getNodes().values());
            List<ServiceEdge> edgesSnapshot = new ArrayList<>(topology.getEdges());

            saveNodesTransactional(nodesSnapshot);
            saveEdgesTransactional(edgesSnapshot);
            verifyAndUpdateCache(topology, nodesSnapshot, edgesSnapshot, version);

            log.info("拓扑保存成功: namespace={}, version={}, nodes={}, edges={}",
                    namespace, version + 1, nodesSnapshot.size(), edgesSnapshot.size());

        } catch (Exception e) {
            log.error("拓扑保存失败，执行回滚: namespace={}, error={}", namespace, e.getMessage(), e);
            rollbackCache(namespace, version);
            throw TopologyConsistencyException.builder("TOPO_SAVE_FAILED", "拓扑保存失败，已回滚")
                    .namespace(namespace)
                    .phase("saveTopology")
                    .expectedNodes(topology.getNodes().size())
                    .expectedEdges(topology.getEdges().size())
                    .cause(e)
                    .recoveryInfo("rollbackVersion", version)
                    .recoveryInfo("recoverable", true)
                    .build();
        } finally {
            writeCounter.decrementAndGet();
        }
    }

    private void saveNodesTransactional(List<ServiceNode> nodes) {
        for (ServiceNode node : nodes) {
            try {
                ServiceNodePO po = ServiceNodePO.fromDomain(node);
                serviceNodeMapper.insertOrUpdate(po);
            } catch (Exception e) {
                log.error("保存节点失败: nodeId={}, serviceName={}", node.getId(), node.getServiceName(), e);
                throw e;
            }
        }
    }

    private void saveEdgesTransactional(List<ServiceEdge> edges) {
        for (ServiceEdge edge : edges) {
            try {
                String key = edge.getSourceServiceName() + "->" + edge.getTargetServiceName();
                edgeCache.put(key, edge);
            } catch (Exception e) {
                log.error("保存边失败: edge={}->{}", edge.getSourceServiceName(), edge.getTargetServiceName(), e);
                throw e;
            }
        }
    }

    private void verifyAndUpdateCache(ServiceTopology topology, List<ServiceNode> nodes,
                                      List<ServiceEdge> edges, long expectedVersion) {
        String namespace = topology.getNamespace();

        int persistedNodes = serviceNodeMapper.selectCount(
                new LambdaQueryWrapper<ServiceNodePO>()
                        .eq(ServiceNodePO::getNamespace, namespace)
        ).intValue();

        if (persistedNodes < nodes.size()) {
            throw new IllegalStateException("节点持久化不完整: expected=" + nodes.size() +
                    ", actual=" + persistedNodes);
        }

        topology.setUpdatedAt(Instant.now());
        topologyCache.put(namespace, topology);
        topologyVersion.put(namespace, expectedVersion + 1);
    }

    private void rollbackCache(String namespace, long previousVersion) {
        topologyCache.remove(namespace);
        topologyVersion.put(namespace, previousVersion);
        log.warn("缓存已回滚: namespace={}, version={}", namespace, previousVersion);
    }

    @Override
    public Optional<ServiceTopology> findTopologyByNamespace(String namespace) {
        ServiceTopology topology = topologyCache.get(namespace);
        if (topology != null) {
            return Optional.of(topology);
        }
        return rebuildTopologyFromDB(namespace);
    }

    private Optional<ServiceTopology> rebuildTopologyFromDB(String namespace) {
        try {
            List<ServiceNodePO> nodePOs = serviceNodeMapper.selectList(
                    new LambdaQueryWrapper<ServiceNodePO>()
                            .eq(ServiceNodePO::getNamespace, namespace)
            );

            if (nodePOs.isEmpty()) {
                return Optional.empty();
            }

            ServiceTopology topology = ServiceTopology.builder()
                    .id("topo_rebuilt_" + namespace)
                    .namespace(namespace)
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .build();

            for (ServiceNodePO po : nodePOs) {
                topology.addNode(po.toDomain());
            }

            for (ServiceEdge edge : edgeCache.values()) {
                topology.addEdge(edge);
            }

            topologyCache.put(namespace, topology);
            log.info("从数据库重建拓扑: namespace={}, nodes={}", namespace, nodePOs.size());
            return Optional.of(topology);

        } catch (Exception e) {
            log.error("从数据库重建拓扑失败: namespace={}", namespace, e);
            return Optional.empty();
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ServiceNode saveNode(ServiceNode node) {
        try {
            ServiceNodePO po = ServiceNodePO.fromDomain(node);
            serviceNodeMapper.insertOrUpdate(po);

            String namespace = node.getNamespace();
            ServiceTopology topology = topologyCache.get(namespace);
            if (topology != null) {
                topology.addNode(node);
                topology.setUpdatedAt(Instant.now());
            }

            return node;
        } catch (Exception e) {
            log.error("保存节点失败: nodeId={}", node.getId(), e);
            throw TopologyConsistencyException.builder("NODE_SAVE_FAILED", "节点保存失败")
                    .namespace(node.getNamespace())
                    .phase("saveNode")
                    .expectedNodes(1)
                    .cause(e)
                    .build();
        }
    }

    @Override
    public Optional<ServiceNode> findNodeById(String nodeId) {
        return Optional.ofNullable(serviceNodeMapper.selectById(nodeId))
                .map(ServiceNodePO::toDomain);
    }

    @Override
    public PageResult<ServiceNode> findNodesByNamespace(String namespace, int pageNum, int pageSize) {
        IPage<ServiceNodePO> page = serviceNodeMapper.findByNamespace(
                new Page<>(pageNum, pageSize), namespace);

        List<ServiceNode> records = page.getRecords().stream()
                .map(ServiceNodePO::toDomain)
                .collect(Collectors.toList());

        return PageResult.of(records, page.getTotal(), pageNum, pageSize);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteNode(String nodeId) {
        try {
            ServiceNodePO po = serviceNodeMapper.selectById(nodeId);
            if (po != null) {
                String namespace = po.getNamespace();
                serviceNodeMapper.deleteById(nodeId);

                ServiceTopology topology = topologyCache.get(namespace);
                if (topology != null) {
                    topology.getNodes().remove(po.getServiceName());
                    topology.setUpdatedAt(Instant.now());
                }
            }
        } catch (Exception e) {
            log.error("删除节点失败: nodeId={}", nodeId, e);
            throw TopologyConsistencyException.builder("NODE_DELETE_FAILED", "节点删除失败")
                    .phase("deleteNode")
                    .cause(e)
                    .build();
        }
    }

    @Override
    public ServiceEdge saveEdge(ServiceEdge edge) {
        String key = edge.getSourceServiceName() + "->" + edge.getTargetServiceName();
        edgeCache.put(key, edge);
        return edge;
    }

    @Override
    public Optional<ServiceEdge> findEdgeByServices(String sourceServiceId, String targetServiceId) {
        return edgeCache.values().stream()
                .filter(e -> sourceServiceId.equals(e.getSourceServiceId())
                        && targetServiceId.equals(e.getTargetServiceId()))
                .findFirst();
    }

    @Override
    public List<ServiceEdge> findEdgesBySourceService(String sourceServiceId) {
        return edgeCache.values().stream()
                .filter(e -> sourceServiceId.equals(e.getSourceServiceId()))
                .collect(Collectors.toList());
    }

    @Override
    public void saveSpan(TraceSpan span) {
        spanCache.computeIfAbsent(span.getTraceId(), k -> new java.util.ArrayList<>())
                .add(span);
    }

    @Override
    public List<TraceSpan> findSpansByTraceId(String traceId) {
        return spanCache.getOrDefault(traceId, List.of());
    }

    public Map<String, Object> getConsistencyStatus(String namespace) {
        Map<String, Object> status = new HashMap<>();
        status.put("namespace", namespace);
        status.put("cacheVersion", topologyVersion.getOrDefault(namespace, 0L));
        status.put("inFlightWrites", inFlightWrites.getOrDefault(namespace, new AtomicLong(0)).get());

        ServiceTopology topology = topologyCache.get(namespace);
        if (topology != null) {
            status.put("cachedNodes", topology.getNodes().size());
            status.put("cachedEdges", topology.getEdges().size());
        }

        int dbNodes = serviceNodeMapper.selectCount(
                new LambdaQueryWrapper<ServiceNodePO>()
                        .eq(ServiceNodePO::getNamespace, namespace)
        ).intValue();
        status.put("dbNodes", dbNodes);

        return status;
    }
}
