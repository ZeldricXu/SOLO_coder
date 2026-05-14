package com.formflow.service;

import com.formflow.engine.ExpressionEngine;
import com.formflow.entity.ProcessDefinition;
import com.formflow.entity.ProcessNode;
import com.formflow.entity.ProcessTransition;
import com.formflow.enums.NodeType;
import com.formflow.exception.BusinessException;
import com.formflow.repository.ProcessDefinitionRepository;
import com.formflow.util.IdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class ProcessDefinitionService {

    private static final Logger logger = LoggerFactory.getLogger(ProcessDefinitionService.class);

    @Autowired
    private ProcessDefinitionRepository processDefinitionRepository;

    @Autowired
    private ExpressionEngine expressionEngine;

    public ProcessDefinition getProcessDefinition(String processId) {
        return processDefinitionRepository.findByProcessId(processId)
                .orElseThrow(() -> new BusinessException(404, "流程定义不存在: " + processId));
    }

    public ProcessDefinition getEnabledProcessDefinition(String processId) {
        return processDefinitionRepository.findByProcessIdAndEnabledTrue(processId)
                .orElseThrow(() -> new BusinessException(404, "流程定义不存在或已禁用: " + processId));
    }

    public List<ProcessDefinition> getAllProcessDefinitions() {
        return processDefinitionRepository.findAll();
    }

    public List<ProcessDefinition> getEnabledProcessDefinitions() {
        return processDefinitionRepository.findByEnabledTrue();
    }

    @Transactional
    public ProcessDefinition createProcessDefinition(ProcessDefinition definition,
                                                     String creatorId, String creatorName) {
        if (processDefinitionRepository.existsByProcessId(definition.getProcessId())) {
            throw new BusinessException("流程ID已存在: " + definition.getProcessId());
        }

        if (definition.getProcessId() == null || definition.getProcessId().isEmpty()) {
            definition.setProcessId(IdGenerator.generateProcessId(null));
        }

        definition.setCreatorId(creatorId);
        definition.setCreatorName(creatorName);
        definition.setVersion(1);
        definition.setEnabled(true);

        validateProcessDefinition(definition);

        ProcessDefinition saved = processDefinitionRepository.save(definition);
        logger.info("创建流程定义成功: {}", saved.getProcessId());
        return saved;
    }

    @Transactional
    public ProcessDefinition updateProcessDefinition(String processId, ProcessDefinition definition) {
        ProcessDefinition existing = getProcessDefinition(processId);

        existing.setProcessName(definition.getProcessName());
        existing.setDescription(definition.getDescription());
        existing.setNodes(definition.getNodes());
        existing.setTransitions(definition.getTransitions());
        existing.setStartNodeId(definition.getStartNodeId());
        existing.setEndNodeId(definition.getEndNodeId());
        existing.setVersion(existing.getVersion() + 1);

        validateProcessDefinition(existing);

        ProcessDefinition saved = processDefinitionRepository.save(existing);
        logger.info("更新流程定义成功: {}", processId);
        return saved;
    }

    @Transactional
    public void deleteProcessDefinition(String processId) {
        ProcessDefinition definition = getProcessDefinition(processId);
        processDefinitionRepository.delete(definition);
        logger.info("删除流程定义成功: {}", processId);
    }

    @Transactional
    public ProcessDefinition enableProcessDefinition(String processId) {
        ProcessDefinition definition = getProcessDefinition(processId);
        definition.setEnabled(true);
        ProcessDefinition saved = processDefinitionRepository.save(definition);
        logger.info("启用流程定义成功: {}", processId);
        return saved;
    }

    @Transactional
    public ProcessDefinition disableProcessDefinition(String processId) {
        ProcessDefinition definition = getProcessDefinition(processId);
        definition.setEnabled(false);
        ProcessDefinition saved = processDefinitionRepository.save(definition);
        logger.info("禁用流程定义成功: {}", processId);
        return saved;
    }

    public ProcessNode getNodeById(ProcessDefinition definition, String nodeId) {
        if (definition.getNodes() == null) {
            return null;
        }
        return definition.getNodes().stream()
                .filter(n -> nodeId.equals(n.getNodeId()))
                .findFirst()
                .orElse(null);
    }

    public ProcessNode getStartNode(ProcessDefinition definition) {
        return getNodeById(definition, definition.getStartNodeId());
    }

    public ProcessNode getEndNode(ProcessDefinition definition) {
        return getNodeById(definition, definition.getEndNodeId());
    }

    public boolean isEndNode(ProcessDefinition definition, String nodeId) {
        return definition.getEndNodeId().equals(nodeId);
    }

    public List<ProcessTransition> getTransitionsFromNode(ProcessDefinition definition, String fromNodeId) {
        if (definition.getTransitions() == null) {
            return Collections.emptyList();
        }
        return definition.getTransitions().stream()
                .filter(t -> fromNodeId.equals(t.getFromNode()))
                .sorted(Comparator.comparing(t -> t.getSortOrder() == null ? 0 : t.getSortOrder()))
                .collect(Collectors.toList());
    }

    public ProcessTransition findNextTransition(ProcessDefinition definition, String fromNodeId,
                                                String condition, Map<String, Object> variables) {
        List<ProcessTransition> transitions = getTransitionsFromNode(definition, fromNodeId);

        for (ProcessTransition transition : transitions) {
            if (matchTransition(transition, condition, variables)) {
                return transition;
            }
        }

        return transitions.stream()
                .filter(t -> "always".equalsIgnoreCase(t.getCondition()))
                .findFirst()
                .orElse(null);
    }

    private boolean matchTransition(ProcessTransition transition, String condition,
                                    Map<String, Object> variables) {
        String transitionCondition = transition.getCondition();

        if (transitionCondition == null || transitionCondition.isEmpty()) {
            return false;
        }

        if ("always".equalsIgnoreCase(transitionCondition)) {
            return true;
        }

        if (condition != null && condition.equalsIgnoreCase(transitionCondition)) {
            return true;
        }

        if (transition.getConditionExpression() != null && !transition.getConditionExpression().isEmpty()) {
            return evaluateConditionExpression(transition.getConditionExpression(), variables);
        }

        return false;
    }

    public boolean evaluateConditionExpression(String expression, Map<String, Object> variables) {
        return expressionEngine.evaluate(expression, variables);
    }

    public Object evaluateExpression(String expression, Map<String, Object> variables) {
        return expressionEngine.evaluateValue(expression, variables);
    }

    private void validateProcessDefinition(ProcessDefinition definition) {
        if (definition.getProcessName() == null || definition.getProcessName().isEmpty()) {
            throw new BusinessException("流程名称不能为空");
        }

        if (definition.getStartNodeId() == null || definition.getStartNodeId().isEmpty()) {
            throw new BusinessException("开始节点不能为空");
        }

        if (definition.getEndNodeId() == null || definition.getEndNodeId().isEmpty()) {
            throw new BusinessException("结束节点不能为空");
        }

        List<ProcessNode> nodes = definition.getNodes();
        if (nodes == null || nodes.isEmpty()) {
            throw new BusinessException("流程节点不能为空");
        }

        Set<String> nodeIds = new HashSet<>();
        boolean hasStartNode = false;
        boolean hasEndNode = false;

        for (int i = 0; i < nodes.size(); i++) {
            ProcessNode node = nodes.get(i);
            if (node.getNodeId() == null || node.getNodeId().isEmpty()) {
                throw new BusinessException("节点ID不能为空，索引: " + i);
            }
            if (node.getNodeName() == null || node.getNodeName().isEmpty()) {
                throw new BusinessException("节点名称不能为空，索引: " + i);
            }
            if (node.getNodeType() == null) {
                throw new BusinessException("节点类型不能为空，索引: " + i);
            }

            if (!nodeIds.add(node.getNodeId())) {
                throw new BusinessException("节点ID重复: " + node.getNodeId());
            }

            if (node.getNodeId().equals(definition.getStartNodeId())) {
                hasStartNode = true;
            }
            if (node.getNodeId().equals(definition.getEndNodeId())) {
                hasEndNode = true;
            }

            if (node.getSortOrder() == null) {
                node.setSortOrder(i);
            }
        }

        if (!hasStartNode) {
            throw new BusinessException("开始节点不存在: " + definition.getStartNodeId());
        }
        if (!hasEndNode) {
            throw new BusinessException("结束节点不存在: " + definition.getEndNodeId());
        }

        if (definition.getTransitions() != null) {
            Set<String> transitionKeys = new HashSet<>();
            for (ProcessTransition transition : definition.getTransitions()) {
                if (transition.getFromNode() == null || transition.getToNode() == null) {
                    throw new BusinessException("流转规则配置不完整");
                }
                if (!nodeIds.contains(transition.getFromNode())) {
                    throw new BusinessException("流转规则源节点不存在: " + transition.getFromNode());
                }
                if (!nodeIds.contains(transition.getToNode())) {
                    throw new BusinessException("流转规则目标节点不存在: " + transition.getToNode());
                }
                String key = transition.getFromNode() + "->" + transition.getToNode();
                if (!transitionKeys.add(key)) {
                    throw new BusinessException("流转规则重复: " + key);
                }
            }
        }
    }
}
