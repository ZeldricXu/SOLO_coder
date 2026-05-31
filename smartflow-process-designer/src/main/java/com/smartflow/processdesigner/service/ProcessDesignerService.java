package com.smartflow.processdesigner.service;

import com.smartflow.common.exception.BusinessException;
import com.smartflow.common.utils.IdGenerator;
import com.smartflow.common.utils.JsonUtils;
import com.smartflow.persistence.entity.ProcessDefinition;
import com.smartflow.persistence.entity.ProcessInstance;
import com.smartflow.persistence.entity.ProcessLine;
import com.smartflow.persistence.entity.ProcessNode;
import com.smartflow.persistence.mapper.ProcessDefinitionMapper;
import com.smartflow.persistence.mapper.ProcessInstanceMapper;
import com.smartflow.persistence.mapper.ProcessLineMapper;
import com.smartflow.persistence.mapper.ProcessNodeMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ProcessDesignerService {

    private final ProcessDefinitionMapper definitionMapper;
    private final ProcessNodeMapper nodeMapper;
    private final ProcessLineMapper lineMapper;
    private final ProcessInstanceMapper instanceMapper;

    @Transactional
    public Map<String, Object> createProcess(ProcessDefinition process, List<ProcessNode> nodes, List<ProcessLine> lines) {
        ProcessDefinition existing = definitionMapper.selectOne(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ProcessDefinition>()
                .eq(ProcessDefinition::getProcessCode, process.getProcessCode())
                .orderByDesc(ProcessDefinition::getVersion)
                .last("LIMIT 1")
        );

        int version = existing != null ? existing.getVersion() + 1 : 1;
        process.setId(IdGenerator.generateId());
        process.setVersion(version);
        process.setEnabled(1);
        process.setNodes(JsonUtils.toJson(nodes));
        process.setLines(JsonUtils.toJson(lines));
        definitionMapper.insert(process);

        for (ProcessNode node : nodes) {
            node.setId(IdGenerator.generateId());
            node.setDefinitionId(process.getId());
            nodeMapper.insert(node);
        }

        for (ProcessLine line : lines) {
            line.setId(IdGenerator.generateId());
            line.setDefinitionId(process.getId());
            lineMapper.insert(line);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("definitionId", process.getId());
        result.put("processCode", process.getProcessCode());
        result.put("version", version);
        return result;
    }

    public Map<String, Object> validateProcess(List<ProcessNode> nodes, List<ProcessLine> lines) {
        Map<String, Object> result = new HashMap<>();
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        if (nodes == null || nodes.isEmpty()) {
            errors.add("流程至少需要一个节点");
        } else {
            long startNodes = nodes.stream().filter(n -> n.getNodeType() != null && n.getNodeType() == 0).count();
            if (startNodes == 0) {
                errors.add("流程需要一个开始节点");
            } else if (startNodes > 1) {
                errors.add("流程只能有一个开始节点");
            }

            long endNodes = nodes.stream().filter(n -> n.getNodeType() != null && n.getNodeType() == 99).count();
            if (endNodes == 0) {
                warnings.add("流程建议至少有一个结束节点");
            }
        }

        if (lines != null && !lines.isEmpty()) {
            Set<Long> nodeIds = nodes.stream().map(ProcessNode::getId).collect(Collectors.toSet());
            for (ProcessLine line : lines) {
                if (!nodeIds.contains(line.getFromNodeId())) {
                    errors.add("连线起始节点不存在: " + line.getFromNodeId());
                }
                if (!nodeIds.contains(line.getToNodeId())) {
                    errors.add("连线目标节点不存在: " + line.getToNodeId());
                }
                if (line.getFromNodeId() != null && line.getFromNodeId().equals(line.getToNodeId())) {
                    errors.add("连线不能连接到自身");
                }
            }

            if (hasCycle(nodes, lines)) {
                errors.add("流程中存在循环依赖");
            }
        }

        result.put("valid", errors.isEmpty());
        result.put("errors", errors);
        result.put("warnings", warnings);
        return result;
    }

    private boolean hasCycle(List<ProcessNode> nodes, List<ProcessLine> lines) {
        Map<Long, List<Long>> adjacency = new HashMap<>();
        for (ProcessNode node : nodes) {
            adjacency.put(node.getId(), new ArrayList<>());
        }
        for (ProcessLine line : lines) {
            adjacency.computeIfAbsent(line.getFromNodeId(), k -> new ArrayList<>())
                    .add(line.getToNodeId());
        }

        Set<Long> visited = new HashSet<>();
        Set<Long> recStack = new HashSet<>();

        for (ProcessNode node : nodes) {
            if (hasCycleDFS(node.getId(), adjacency, visited, recStack)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasCycleDFS(Long nodeId, Map<Long, List<Long>> adjacency, Set<Long> visited, Set<Long> recStack) {
        if (recStack.contains(nodeId)) {
            return true;
        }
        if (visited.contains(nodeId)) {
            return false;
        }

        visited.add(nodeId);
        recStack.add(nodeId);

        for (Long neighbor : adjacency.getOrDefault(nodeId, Collections.emptyList())) {
            if (hasCycleDFS(neighbor, adjacency, visited, recStack)) {
                return true;
            }
        }

        recStack.remove(nodeId);
        return false;
    }

    public ProcessDefinition getProcessDefinition(Long definitionId) {
        ProcessDefinition definition = definitionMapper.selectById(definitionId);
        if (definition == null) {
            throw new BusinessException("流程定义不存在");
        }
        return definition;
    }

    public Map<String, Object> getProcessDetail(Long definitionId) {
        ProcessDefinition definition = getProcessDefinition(definitionId);
        List<ProcessNode> nodes = nodeMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ProcessNode>()
                .eq(ProcessNode::getDefinitionId, definitionId)
                .orderByAsc(ProcessNode::getSortOrder)
        );
        List<ProcessLine> lines = lineMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ProcessLine>()
                .eq(ProcessLine::getDefinitionId, definitionId)
                .orderByAsc(ProcessLine::getSortOrder)
        );

        Map<String, Object> result = new HashMap<>();
        result.put("definition", definition);
        result.put("nodes", nodes);
        result.put("lines", lines);
        return result;
    }

    public List<ProcessDefinition> listProcessDefinitions(String processType, String category) {
        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ProcessDefinition> query = 
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ProcessDefinition>()
                .eq(ProcessDefinition::getEnabled, 1)
                .orderByDesc(ProcessDefinition::getCreatedAt);

        if (processType != null && !processType.isEmpty()) {
            query.eq(ProcessDefinition::getProcessType, processType);
        }
        if (category != null && !category.isEmpty()) {
            query.eq(ProcessDefinition::getCategory, category);
        }

        return definitionMapper.selectList(query);
    }

    @Transactional
    public ProcessInstance startProcess(Long definitionId, Long businessId, String businessType, Map<String, Object> variables) {
        ProcessDefinition definition = getProcessDefinition(definitionId);

        ProcessInstance instance = new ProcessInstance();
        instance.setId(IdGenerator.generateId());
        instance.setDefinitionId(definitionId);
        instance.setProcessCode(definition.getProcessCode());
        instance.setProcessName(definition.getProcessName());
        instance.setBusinessId(businessId);
        instance.setBusinessType(businessType);
        instance.setStatus(0);
        instance.setVariables(JsonUtils.toJson(variables));
        instance.setStartTime(LocalDateTime.now());

        List<ProcessNode> nodes = nodeMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ProcessNode>()
                .eq(ProcessNode::getDefinitionId, definitionId)
                .eq(ProcessNode::getNodeType, 0)
        );

        if (!nodes.isEmpty()) {
            instance.setCurrentNodeId(nodes.get(0).getId());
        }

        instanceMapper.insert(instance);
        return instance;
    }

    @Transactional
    public Map<String, Object> executeNode(Long instanceId, Long nodeId, Map<String, Object> variables) {
        ProcessInstance instance = instanceMapper.selectById(instanceId);
        if (instance == null) {
            throw new BusinessException("流程实例不存在");
        }
        if (instance.getStatus() != 0) {
            throw new BusinessException("流程已结束或终止");
        }

        List<ProcessLine> outgoingLines = lineMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ProcessLine>()
                .eq(ProcessLine::getDefinitionId, instance.getDefinitionId())
                .eq(ProcessLine::getFromNodeId, nodeId)
                .orderByAsc(ProcessLine::getSortOrder)
        );

        ProcessNode nextNode = null;
        for (ProcessLine line : outgoingLines) {
            if (evaluateCondition(line.getConditionExpression(), variables)) {
                nextNode = nodeMapper.selectById(line.getToNodeId());
                break;
            }
        }

        Map<String, Object> result = new HashMap<>();
        if (nextNode == null || nextNode.getNodeType() == 99) {
            instance.setStatus(1);
            instance.setEndTime(LocalDateTime.now());
            instanceMapper.updateById(instance);
            result.put("completed", true);
            result.put("message", "流程已完成");
        } else {
            instance.setCurrentNodeId(nextNode.getId());
            instanceMapper.updateById(instance);
            result.put("completed", false);
            result.put("nextNode", nextNode);
        }

        return result;
    }

    private boolean evaluateCondition(String conditionExpression, Map<String, Object> variables) {
        if (conditionExpression == null || conditionExpression.isEmpty()) {
            return true;
        }

        try {
            if (conditionExpression.startsWith("${") && conditionExpression.endsWith("}")) {
                String expr = conditionExpression.substring(2, conditionExpression.length() - 1);
                String[] parts = expr.split("[=<>!]+");
                if (parts.length == 2) {
                    String key = parts[0].trim();
                    String op = expr.replace(parts[0], "").replace(parts[1], "").trim();
                    String expected = parts[1].trim().replace("'", "");
                    Object actual = variables != null ? variables.get(key) : null;
                    if (actual == null) {
                        return false;
                    }
                    switch (op) {
                        case "==":
                        case "=":
                            return expected.equals(actual.toString());
                        case "!=":
                        case "<>":
                            return !expected.equals(actual.toString());
                        case ">":
                            return Double.parseDouble(actual.toString()) > Double.parseDouble(expected);
                        case "<":
                            return Double.parseDouble(actual.toString()) < Double.parseDouble(expected);
                        case ">=":
                            return Double.parseDouble(actual.toString()) >= Double.parseDouble(expected);
                        case "<=":
                            return Double.parseDouble(actual.toString()) <= Double.parseDouble(expected);
                    }
                }
            }
        } catch (Exception e) {
            return false;
        }

        return true;
    }

    public ProcessInstance getProcessInstance(Long instanceId) {
        ProcessInstance instance = instanceMapper.selectById(instanceId);
        if (instance == null) {
            throw new BusinessException("流程实例不存在");
        }
        return instance;
    }

    @Transactional
    public boolean terminateProcess(Long instanceId) {
        ProcessInstance instance = getProcessInstance(instanceId);
        instance.setStatus(2);
        instance.setEndTime(LocalDateTime.now());
        instanceMapper.updateById(instance);
        return true;
    }
}
