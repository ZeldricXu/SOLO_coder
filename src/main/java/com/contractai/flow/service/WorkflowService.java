package com.contractai.flow.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.contractai.common.context.TenantContext;
import com.contractai.common.dto.PageQuery;
import com.contractai.common.dto.PageResult;
import com.contractai.common.exception.BusinessException;
import com.contractai.common.exception.ValidationException;
import com.contractai.flow.dto.*;
import com.contractai.flow.entity.*;
import com.contractai.flow.mapper.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowService {

    private final WorkflowDefinitionMapper definitionMapper;
    private final WorkflowNodeMapper nodeMapper;
    private final WorkflowEdgeMapper edgeMapper;
    private final WorkflowInstanceMapper instanceMapper;

    private static final Set<String> VALID_NODE_TYPES = Set.of(
            "start", "end", "approval", "condition", "parallel", "subflow", "service"
    );

    @Transactional(rollbackFor = Exception.class)
    public WorkflowDefinition createWorkflow(WorkflowCreateDTO dto) {
        Long tenantId = TenantContext.getTenantId();
        if (!StringUtils.hasText(dto.getFlowCode())) {
            throw new ValidationException("流程编码不能为空");
        }
        if (!StringUtils.hasText(dto.getFlowName())) {
            throw new ValidationException("流程名称不能为空");
        }
        if (dto.getNodes() == null || dto.getNodes().isEmpty()) {
            throw new ValidationException("流程节点不能为空");
        }
        if (dto.getEdges() == null || dto.getEdges().isEmpty()) {
            throw new ValidationException("流程连线不能为空");
        }

        FlowValidationResult validation = validateWorkflow(dto.getNodes(), dto.getEdges());
        if (!validation.isValid()) {
            throw new ValidationException("流程校验失败: " + String.join(", ", validation.getErrors()));
        }

        Integer maxVersion = definitionMapper.selectObjs(
                new LambdaQueryWrapper<WorkflowDefinition>()
                        .eq(WorkflowDefinition::getTenantId, tenantId)
                        .eq(WorkflowDefinition::getFlowCode, dto.getFlowCode())
                        .orderByDesc(WorkflowDefinition::getVersion)
                        .last("limit 1")
        ).stream().findFirst().map(o -> (Integer) o).orElse(0);

        WorkflowDefinition definition = new WorkflowDefinition();
        definition.setTenantId(tenantId);
        definition.setFlowCode(dto.getFlowCode());
        definition.setFlowName(dto.getFlowName());
        definition.setVersion(maxVersion + 1);
        definition.setCategory(dto.getCategory());
        definition.setDescription(dto.getDescription());
        definition.setStatus("draft");
        definition.setNodes(dto.getNodes());
        definition.setEdges(dto.getEdges());
        definition.setVariables(dto.getVariables());
        definition.setFormSchema(dto.getFormSchema());

        definitionMapper.insert(definition);

        saveNodesAndEdges(definition.getId(), dto.getNodes(), dto.getEdges(), tenantId);

        log.info("创建流程定义成功: tenantId={}, flowCode={}, version={}", 
                tenantId, dto.getFlowCode(), definition.getVersion());
        return definition;
    }

    private void saveNodesAndEdges(Long flowId, List<Map<String, Object>> nodes, 
                                   List<Map<String, Object>> edges, Long tenantId) {
        for (Map<String, Object> nodeMap : nodes) {
            WorkflowNode node = new WorkflowNode();
            node.setTenantId(tenantId);
            node.setFlowId(flowId);
            node.setNodeId((String) nodeMap.get("nodeId"));
            node.setNodeName((String) nodeMap.get("nodeName"));
            node.setNodeType((String) nodeMap.get("nodeType"));
            node.setPositionX(nodeMap.get("positionX") != null ? ((Number) nodeMap.get("positionX")).intValue() : null);
            node.setPositionY(nodeMap.get("positionY") != null ? ((Number) nodeMap.get("positionY")).intValue() : null);
            @SuppressWarnings("unchecked")
            Map<String, Object> config = (Map<String, Object>) nodeMap.get("config");
            node.setConfig(config);
            @SuppressWarnings("unchecked")
            Map<String, Object> formSchema = (Map<String, Object>) nodeMap.get("formSchema");
            node.setFormSchema(formSchema);
            nodeMapper.insert(node);
        }

        for (Map<String, Object> edgeMap : edges) {
            WorkflowEdge edge = new WorkflowEdge();
            edge.setTenantId(tenantId);
            edge.setFlowId(flowId);
            edge.setEdgeId((String) edgeMap.get("edgeId"));
            edge.setSourceNodeId((String) edgeMap.get("sourceNodeId"));
            edge.setTargetNodeId((String) edgeMap.get("targetNodeId"));
            edge.setEdgeName((String) edgeMap.get("edgeName"));
            edge.setConditionExpression((String) edgeMap.get("conditionExpression"));
            edge.setPriority(edgeMap.get("priority") != null ? ((Number) edgeMap.get("priority")).intValue() : 0);
            edgeMapper.insert(edge);
        }
    }

    public FlowValidationResult validateWorkflow(List<Map<String, Object>> nodes, List<Map<String, Object>> edges) {
        FlowValidationResult result = new FlowValidationResult();
        result.setValid(true);
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        Set<String> nodeIds = nodes.stream()
                .map(n -> (String) n.get("nodeId"))
                .collect(Collectors.toSet());

        long startCount = nodes.stream()
                .filter(n -> "start".equals(n.get("nodeType")))
                .count();
        if (startCount == 0) {
            errors.add("流程必须包含一个开始节点");
        } else if (startCount > 1) {
            errors.add("流程只能有一个开始节点");
        }

        long endCount = nodes.stream()
                .filter(n -> "end".equals(n.get("nodeType")))
                .count();
        if (endCount == 0) {
            errors.add("流程必须包含至少一个结束节点");
        }

        for (Map<String, Object> node : nodes) {
            String nodeType = (String) node.get("nodeType");
            if (!VALID_NODE_TYPES.contains(nodeType)) {
                errors.add("无效的节点类型: " + nodeType);
            }
        }

        for (Map<String, Object> edge : edges) {
            String sourceId = (String) edge.get("sourceNodeId");
            String targetId = (String) edge.get("targetNodeId");
            
            if (!nodeIds.contains(sourceId)) {
                errors.add("连线源节点不存在: " + sourceId);
            }
            if (!nodeIds.contains(targetId)) {
                errors.add("连线目标节点不存在: " + targetId);
            }
            if (sourceId != null && sourceId.equals(targetId)) {
                errors.add("连线不能连接到自身: " + sourceId);
            }
        }

        Set<String> sourceNodeIds = edges.stream()
                .map(e -> (String) e.get("sourceNodeId"))
                .collect(Collectors.toSet());
        Set<String> targetNodeIds = edges.stream()
                .map(e -> (String) e.get("targetNodeId"))
                .collect(Collectors.toSet());

        for (String nodeId : nodeIds) {
            Map<String, Object> node = nodes.stream()
                    .filter(n -> nodeId.equals(n.get("nodeId")))
                    .findFirst().orElse(null);
            if (node == null) continue;
            
            String nodeType = (String) node.get("nodeType");
            if (!"end".equals(nodeType) && !sourceNodeIds.contains(nodeId)) {
                warnings.add("节点没有出边: " + node.get("nodeName"));
            }
            if (!"start".equals(nodeType) && !targetNodeIds.contains(nodeId)) {
                warnings.add("节点没有入边: " + node.get("nodeName"));
            }
        }

        for (Map<String, Object> node : nodes) {
            if ("condition".equals(node.get("nodeType"))) {
                String nodeId = (String) node.get("nodeId");
                long outEdgeCount = edges.stream()
                        .filter(e -> nodeId.equals(e.get("sourceNodeId")))
                        .count();
                if (outEdgeCount < 2) {
                    warnings.add("条件节点至少需要2条出边: " + node.get("nodeName"));
                }
            }
        }

        if (hasCycle(nodes, edges)) {
            errors.add("流程存在循环引用");
        }

        result.setErrors(errors);
        result.setWarnings(warnings);
        result.setValid(errors.isEmpty());

        return result;
    }

    private boolean hasCycle(List<Map<String, Object>> nodes, List<Map<String, Object>> edges) {
        Map<String, List<String>> adjacency = new HashMap<>();
        for (Map<String, Object> node : nodes) {
            adjacency.put((String) node.get("nodeId"), new ArrayList<>());
        }
        for (Map<String, Object> edge : edges) {
            String source = (String) edge.get("sourceNodeId");
            String target = (String) edge.get("targetNodeId");
            adjacency.computeIfAbsent(source, k -> new ArrayList<>()).add(target);
        }

        Set<String> visited = new HashSet<>();
        Set<String> recStack = new HashSet<>();

        for (Map<String, Object> node : nodes) {
            String nodeId = (String) node.get("nodeId");
            if (dfsHasCycle(nodeId, adjacency, visited, recStack)) {
                return true;
            }
        }
        return false;
    }

    private boolean dfsHasCycle(String nodeId, Map<String, List<String>> adjacency,
                                Set<String> visited, Set<String> recStack) {
        if (recStack.contains(nodeId)) return true;
        if (visited.contains(nodeId)) return false;

        visited.add(nodeId);
        recStack.add(nodeId);

        for (String neighbor : adjacency.getOrDefault(nodeId, Collections.emptyList())) {
            if (dfsHasCycle(neighbor, adjacency, visited, recStack)) {
                return true;
            }
        }

        recStack.remove(nodeId);
        return false;
    }

    public PageResult<WorkflowDefinition> listWorkflows(PageQuery query) {
        Long tenantId = TenantContext.getTenantId();
        Page<WorkflowDefinition> page = new Page<>(query.getPageNum(), query.getPageSize());
        
        LambdaQueryWrapper<WorkflowDefinition> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(WorkflowDefinition::getTenantId, tenantId)
               .orderByDesc(WorkflowDefinition::getCreatedAt);
        
        definitionMapper.selectPage(page, wrapper);
        return new PageResult<>(page.getTotal(), page.getRecords(), query.getPageNum(), query.getPageSize());
    }

    public WorkflowDefinition getWorkflow(Long id) {
        Long tenantId = TenantContext.getTenantId();
        WorkflowDefinition definition = definitionMapper.selectOne(
                new LambdaQueryWrapper<WorkflowDefinition>()
                        .eq(WorkflowDefinition::getTenantId, tenantId)
                        .eq(WorkflowDefinition::getId, id));
        if (definition == null) {
            throw new BusinessException(404, "流程定义不存在");
        }
        return definition;
    }

    @Transactional(rollbackFor = Exception.class)
    public WorkflowDefinition publishWorkflow(Long id) {
        WorkflowDefinition definition = getWorkflow(id);
        definition.setStatus("published");
        definition.setPublishedAt(LocalDateTime.now());
        definition.setPublishedBy(TenantContext.getTenantIdSafe());
        definitionMapper.updateById(definition);
        log.info("发布流程: id={}, flowCode={}", id, definition.getFlowCode());
        return definition;
    }

    @Transactional(rollbackFor = Exception.class)
    public WorkflowInstance startInstance(InstanceStartDTO dto) {
        Long tenantId = TenantContext.getTenantId();
        if (dto.getFlowId() == null) {
            throw new ValidationException("请选择流程");
        }

        WorkflowDefinition definition = getWorkflow(dto.getFlowId());
        if (!"published".equals(definition.getStatus())) {
            throw new BusinessException(400, "流程未发布，不能启动");
        }

        String instanceNo = "WF-" + System.currentTimeMillis() + "-" + 
                UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        WorkflowInstance instance = new WorkflowInstance();
        instance.setTenantId(tenantId);
        instance.setInstanceNo(instanceNo);
        instance.setFlowId(dto.getFlowId());
        instance.setBusinessKey(dto.getBusinessKey());
        instance.setStatus("running");
        instance.setVariables(dto.getVariables());
        instance.setFormData(dto.getFormData());
        instance.setStartedAt(LocalDateTime.now());
        instance.setStartedBy(dto.getStartedBy());

        String startNodeId = definition.getNodes().stream()
                .filter(n -> "start".equals(n.get("nodeType")))
                .map(n -> (String) n.get("nodeId"))
                .findFirst()
                .orElseThrow(() -> new BusinessException(400, "流程没有开始节点"));
        
        instance.setCurrentNodeId(startNodeId);

        instanceMapper.insert(instance);
        log.info("启动流程实例: instanceNo={}, flowId={}", instanceNo, dto.getFlowId());

        proceedToNextNode(instance, definition);
        
        return instance;
    }

    private void proceedToNextNode(WorkflowInstance instance, WorkflowDefinition definition) {
        String currentNodeId = instance.getCurrentNodeId();
        
        List<Map<String, Object>> outEdges = definition.getEdges().stream()
                .filter(e -> currentNodeId.equals(e.get("sourceNodeId")))
                .sorted((a, b) -> {
                    Integer pa = a.get("priority") != null ? (Integer) a.get("priority") : 0;
                    Integer pb = b.get("priority") != null ? (Integer) b.get("priority") : 0;
                    return Integer.compare(pb, pa);
                })
                .collect(Collectors.toList());

        if (outEdges.isEmpty()) {
            completeInstance(instance);
            return;
        }

        Map<String, Object> currentNode = definition.getNodes().stream()
                .filter(n -> currentNodeId.equals(n.get("nodeId")))
                .findFirst()
                .orElse(null);

        if (currentNode != null && "condition".equals(currentNode.get("nodeType"))) {
            Map<String, Object> variables = instance.getVariables();
            Map<String, Object> formData = instance.getFormData();
            
            for (Map<String, Object> edge : outEdges) {
                String condition = (String) edge.get("conditionExpression");
                if (condition == null || evaluateCondition(condition, variables, formData)) {
                    String nextNodeId = (String) edge.get("targetNodeId");
                    instance.setCurrentNodeId(nextNodeId);
                    instanceMapper.updateById(instance);
                    
                    Map<String, Object> nextNode = definition.getNodes().stream()
                            .filter(n -> nextNodeId.equals(n.get("nodeId")))
                            .findFirst().orElse(null);
                    
                    if (nextNode != null && "end".equals(nextNode.get("nodeType"))) {
                        completeInstance(instance);
                    }
                    return;
                }
            }
            throw new BusinessException(400, "没有匹配的条件分支");
        } else {
            String nextNodeId = (String) outEdges.get(0).get("targetNodeId");
            instance.setCurrentNodeId(nextNodeId);
            instanceMapper.updateById(instance);
            
            Map<String, Object> nextNode = definition.getNodes().stream()
                    .filter(n -> nextNodeId.equals(n.get("nodeId")))
                    .findFirst().orElse(null);
            
            if (nextNode != null && "end".equals(nextNode.get("nodeType"))) {
                completeInstance(instance);
            }
        }
    }

    private boolean evaluateCondition(String condition, Map<String, Object> variables, Map<String, Object> formData) {
        if (condition == null || condition.trim().isEmpty()) {
            return true;
        }
        try {
            String expr = condition;
            if (variables != null) {
                for (Map.Entry<String, Object> entry : variables.entrySet()) {
                    expr = expr.replace("${" + entry.getKey() + "}", 
                            entry.getValue() != null ? entry.getValue().toString() : "null");
                }
            }
            if (formData != null) {
                for (Map.Entry<String, Object> entry : formData.entrySet()) {
                    expr = expr.replace("${form." + entry.getKey() + "}", 
                            entry.getValue() != null ? entry.getValue().toString() : "null");
                }
            }
            return evaluateSimpleExpression(expr);
        } catch (Exception e) {
            log.warn("条件表达式求值失败: {}, error: {}", condition, e.getMessage());
            return false;
        }
    }

    private boolean evaluateSimpleExpression(String expr) {
        if (expr.contains("==")) {
            String[] parts = expr.split("==");
            return parts[0].trim().equals(parts[1].trim());
        }
        if (expr.contains("!=")) {
            String[] parts = expr.split("!=");
            return !parts[0].trim().equals(parts[1].trim());
        }
        if (expr.contains(">=")) {
            String[] parts = expr.split(">=");
            return Double.parseDouble(parts[0].trim()) >= Double.parseDouble(parts[1].trim());
        }
        if (expr.contains("<=")) {
            String[] parts = expr.split("<=");
            return Double.parseDouble(parts[0].trim()) <= Double.parseDouble(parts[1].trim());
        }
        if (expr.contains(">")) {
            String[] parts = expr.split(">");
            return Double.parseDouble(parts[0].trim()) > Double.parseDouble(parts[1].trim());
        }
        if (expr.contains("<")) {
            String[] parts = expr.split("<");
            return Double.parseDouble(parts[0].trim()) < Double.parseDouble(parts[1].trim());
        }
        return Boolean.parseBoolean(expr.trim());
    }

    private void completeInstance(WorkflowInstance instance) {
        instance.setStatus("completed");
        instance.setEndedAt(LocalDateTime.now());
        instanceMapper.updateById(instance);
        log.info("流程实例完成: instanceNo={}", instance.getInstanceNo());
    }

    public PageResult<WorkflowInstance> listInstances(PageQuery query) {
        Long tenantId = TenantContext.getTenantId();
        Page<WorkflowInstance> page = new Page<>(query.getPageNum(), query.getPageSize());
        
        LambdaQueryWrapper<WorkflowInstance> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(WorkflowInstance::getTenantId, tenantId)
               .orderByDesc(WorkflowInstance::getCreatedAt);
        
        instanceMapper.selectPage(page, wrapper);
        return new PageResult<>(page.getTotal(), page.getRecords(), query.getPageNum(), query.getPageSize());
    }

    public WorkflowInstance getInstance(Long id) {
        Long tenantId = TenantContext.getTenantId();
        WorkflowInstance instance = instanceMapper.selectOne(
                new LambdaQueryWrapper<WorkflowInstance>()
                        .eq(WorkflowInstance::getTenantId, tenantId)
                        .eq(WorkflowInstance::getId, id));
        if (instance == null) {
            throw new BusinessException(404, "流程实例不存在");
        }
        return instance;
    }

    public List<WorkflowNode> getFlowNodes(Long flowId) {
        Long tenantId = TenantContext.getTenantId();
        return nodeMapper.selectList(
                new LambdaQueryWrapper<WorkflowNode>()
                        .eq(WorkflowNode::getTenantId, tenantId)
                        .eq(WorkflowNode::getFlowId, flowId));
    }

    public List<WorkflowEdge> getFlowEdges(Long flowId) {
        Long tenantId = TenantContext.getTenantId();
        return edgeMapper.selectList(
                new LambdaQueryWrapper<WorkflowEdge>()
                        .eq(WorkflowEdge::getTenantId, tenantId)
                        .eq(WorkflowEdge::getFlowId, flowId));
    }
}
