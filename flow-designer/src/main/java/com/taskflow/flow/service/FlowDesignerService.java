package com.taskflow.flow.service;

import com.taskflow.common.exception.ResourceNotFoundException;
import com.taskflow.common.utils.IdGenerator;
import com.taskflow.flow.model.FlowDefinition;
import com.taskflow.flow.model.FlowInstance;
import com.taskflow.flow.model.FlowNode;
import com.taskflow.flow.model.ValidationResult;
import com.taskflow.flow.validator.FlowValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class FlowDesignerService {

    private final FlowValidator flowValidator;
    private final Map<String, FlowDefinition> flowStore = new ConcurrentHashMap<>();
    private final Map<String, FlowInstance> instanceStore = new ConcurrentHashMap<>();

    public FlowDefinition createFlow(String tenantId, FlowDefinition flow) {
        String flowId = IdGenerator.generateId("flow");
        FlowDefinition newFlow = FlowDefinition.builder()
                .flowId(flowId)
                .tenantId(tenantId)
                .name(flow.getName())
                .description(flow.getDescription())
                .version(1)
                .nodes(flow.getNodes() != null ? flow.getNodes() : new ArrayList<>())
                .edges(flow.getEdges() != null ? flow.getEdges() : new ArrayList<>())
                .status("draft")
                .variables(flow.getVariables())
                .config(flow.getConfig())
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .createdBy(flow.getCreatedBy())
                .build();

        ValidationResult validation = flowValidator.validate(newFlow);
        if (!validation.isValid()) {
            newFlow.setStatus("invalid");
        }

        flowStore.put(flowId, newFlow);
        log.info("Flow created: {} - {}", flowId, flow.getName());
        return newFlow;
    }

    public FlowDefinition updateFlow(String tenantId, String flowId, FlowDefinition flow) {
        FlowDefinition existing = getFlow(tenantId, flowId);

        FlowDefinition updated = FlowDefinition.builder()
                .flowId(flowId)
                .tenantId(tenantId)
                .name(flow.getName() != null ? flow.getName() : existing.getName())
                .description(flow.getDescription() != null ? flow.getDescription() : existing.getDescription())
                .version(existing.getVersion() + 1)
                .nodes(flow.getNodes() != null ? flow.getNodes() : existing.getNodes())
                .edges(flow.getEdges() != null ? flow.getEdges() : existing.getEdges())
                .status("draft")
                .variables(flow.getVariables() != null ? flow.getVariables() : existing.getVariables())
                .config(flow.getConfig() != null ? flow.getConfig() : existing.getConfig())
                .createdAt(existing.getCreatedAt())
                .updatedAt(LocalDateTime.now())
                .createdBy(existing.getCreatedBy())
                .build();

        ValidationResult validation = flowValidator.validate(updated);
        if (!validation.isValid()) {
            updated.setStatus("invalid");
        }

        flowStore.put(flowId, updated);
        log.info("Flow updated: {}", flowId);
        return updated;
    }

    public FlowDefinition getFlow(String tenantId, String flowId) {
        FlowDefinition flow = flowStore.get(flowId);
        if (flow == null || !flow.getTenantId().equals(tenantId)) {
            throw new ResourceNotFoundException("Flow", flowId);
        }
        return flow;
    }

    public List<FlowDefinition> listFlows(String tenantId) {
        return flowStore.values().stream()
                .filter(f -> f.getTenantId().equals(tenantId))
                .sorted(Comparator.comparing(FlowDefinition::getUpdatedAt).reversed())
                .toList();
    }

    public void deleteFlow(String tenantId, String flowId) {
        FlowDefinition flow = getFlow(tenantId, flowId);
        flowStore.remove(flowId);
        log.info("Flow deleted: {}", flowId);
    }

    public ValidationResult validateFlow(String tenantId, String flowId) {
        FlowDefinition flow = getFlow(tenantId, flowId);
        return flowValidator.validate(flow);
    }

    public FlowDefinition publishFlow(String tenantId, String flowId) {
        FlowDefinition flow = getFlow(tenantId, flowId);

        ValidationResult validation = flowValidator.validate(flow);
        if (!validation.isValid()) {
            throw new RuntimeException("Flow validation failed: " + validation.getErrors());
        }

        flow.setStatus("published");
        flow.setUpdatedAt(LocalDateTime.now());
        flowStore.put(flowId, flow);
        log.info("Flow published: {}", flowId);
        return flow;
    }

    public FlowInstance startFlow(String tenantId, String flowId, Map<String, Object> inputVariables) {
        FlowDefinition flow = getFlow(tenantId, flowId);
        if (!"published".equals(flow.getStatus())) {
            throw new RuntimeException("Flow is not published");
        }

        String instanceId = IdGenerator.generateId("inst");
        FlowInstance instance = FlowInstance.builder()
                .instanceId(instanceId)
                .flowId(flowId)
                .flowVersion(flow.getVersion())
                .tenantId(tenantId)
                .status("running")
                .currentNodeId(findStartNode(flow))
                .variables(inputVariables != null ? new HashMap<>(inputVariables) : new HashMap<>())
                .executionHistory(new ArrayList<>())
                .startedAt(LocalDateTime.now())
                .build();

        instanceStore.put(instanceId, instance);
        log.info("Flow instance started: {}", instanceId);
        return instance;
    }

    private String findStartNode(FlowDefinition flow) {
        return flow.getNodes().stream()
                .filter(n -> "start".equals(n.getNodeType()))
                .map(FlowNode::getNodeId)
                .findFirst()
                .orElse(null);
    }

    public FlowInstance getFlowInstance(String tenantId, String instanceId) {
        FlowInstance instance = instanceStore.get(instanceId);
        if (instance == null || !instance.getTenantId().equals(tenantId)) {
            throw new ResourceNotFoundException("FlowInstance", instanceId);
        }
        return instance;
    }

    public List<FlowInstance> listFlowInstances(String tenantId, String flowId) {
        return instanceStore.values().stream()
                .filter(i -> i.getTenantId().equals(tenantId) && (flowId == null || flowId.equals(i.getFlowId())))
                .sorted(Comparator.comparing(FlowInstance::getStartedAt).reversed())
                .toList();
    }

    public FlowNode addNode(String tenantId, String flowId, FlowNode node) {
        FlowDefinition flow = getFlow(tenantId, flowId);
        node.setNodeId(IdGenerator.generateId("node"));
        flow.getNodes().add(node);
        flow.setUpdatedAt(LocalDateTime.now());
        flowStore.put(flowId, flow);
        return node;
    }

    public void removeNode(String tenantId, String flowId, String nodeId) {
        FlowDefinition flow = getFlow(tenantId, flowId);
        flow.getNodes().removeIf(n -> nodeId.equals(n.getNodeId()));
        flow.getEdges().removeIf(e -> nodeId.equals(e.getSourceNodeId()) || nodeId.equals(e.getTargetNodeId()));
        flow.setUpdatedAt(LocalDateTime.now());
        flowStore.put(flowId, flow);
    }

    public FlowNode updateNode(String tenantId, String flowId, String nodeId, FlowNode node) {
        FlowDefinition flow = getFlow(tenantId, flowId);
        flow.getNodes().stream()
                .filter(n -> nodeId.equals(n.getNodeId()))
                .findFirst()
                .ifPresent(existing -> {
                    if (node.getName() != null) existing.setName(node.getName());
                    if (node.getConfig() != null) existing.setConfig(node.getConfig());
                    if (node.getPosition() != null) existing.setPosition(node.getPosition());
                });
        flow.setUpdatedAt(LocalDateTime.now());
        flowStore.put(flowId, flow);
        return node;
    }
}
