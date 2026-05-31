package com.orchestration.flowdesigner.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.orchestration.common.exception.BusinessException;
import com.orchestration.common.util.JsonUtil;
import com.orchestration.flowdesigner.service.FlowDesignerService;
import com.orchestration.persistence.entity.FlowDesign;
import com.orchestration.persistence.mapper.FlowDesignMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
@RequiredArgsConstructor
public class FlowDesignerServiceImpl implements FlowDesignerService {

    private final FlowDesignMapper flowDesignMapper;

    @Override
    public Long createDesign(FlowDesign design) {
        FlowDesign existing = flowDesignMapper.selectOne(
                new LambdaQueryWrapper<FlowDesign>()
                        .eq(FlowDesign::getDesignCode, design.getDesignCode())
        );
        if (existing != null) {
            throw new BusinessException("设计编码已存在");
        }
        design.setStatus("draft");
        flowDesignMapper.insert(design);
        return design.getId();
    }

    @Override
    public boolean updateDesign(FlowDesign design) {
        return flowDesignMapper.updateById(design) > 0;
    }

    @Override
    public FlowDesign getDesign(Long id) {
        return flowDesignMapper.selectById(id);
    }

    @Override
    public List<FlowDesign> listDesigns(String flowType, String status) {
        return flowDesignMapper.selectList(
                new LambdaQueryWrapper<FlowDesign>()
                        .eq(flowType != null, FlowDesign::getFlowType, flowType)
                        .eq(status != null, FlowDesign::getStatus, status)
                        .orderByDesc(FlowDesign::getCreatedAt)
        );
    }

    @Override
    public boolean deleteDesign(Long id) {
        return flowDesignMapper.deleteById(id) > 0;
    }

    @Override
    @Transactional
    public boolean publishDesign(Long id) {
        FlowDesign design = flowDesignMapper.selectById(id);
        if (design == null) {
            throw new BusinessException("设计不存在");
        }

        Map<String, Object> designData = JsonUtil.fromJson(design.getDesignData(), Map.class);
        validateDesign(designData);

        design.setStatus("published");
        design.setVersion(design.getVersion() + 1);
        return flowDesignMapper.updateById(design) > 0;
    }

    @Override
    public Map<String, Object> validateDesign(Map<String, Object> designData) {
        Map<String, Object> result = new HashMap<>();
        List<String> errors = new ArrayList<>();

        List<Map<String, Object>> nodes = (List<Map<String, Object>>) designData.getOrDefault("nodes", Collections.emptyList());
        List<Map<String, Object>> edges = (List<Map<String, Object>>) designData.getOrDefault("edges", Collections.emptyList());

        if (nodes.isEmpty()) {
            errors.add("流程至少需要一个节点");
        }

        long startNodes = nodes.stream().filter(n -> "start".equals(n.get("type"))).count();
        if (startNodes == 0) {
            errors.add("流程必须包含一个开始节点");
        } else if (startNodes > 1) {
            errors.add("流程只能包含一个开始节点");
        }

        long endNodes = nodes.stream().filter(n -> "end".equals(n.get("type"))).count();
        if (endNodes == 0) {
            errors.add("流程必须包含一个结束节点");
        }

        Set<String> nodeIds = new HashSet<>();
        for (Map<String, Object> node : nodes) {
            String nodeId = (String) node.get("id");
            if (nodeId == null || nodeId.isEmpty()) {
                errors.add("节点ID不能为空");
            } else if (!nodeIds.contains(nodeId)) {
                errors.add("节点ID重复: " + nodeId);
            } else {
                nodeIds.add(nodeId);
            }

            Map<String, Object> nodeResult = validateNode(node);
            if (!((List<String>) nodeResult.get("errors"))).isEmpty()) {
                errors.addAll((List<String>) nodeResult.get("errors"));
            }
        }

        for (Map<String, Object> edge : edges) {
            Map<String, Object> edgeResult = validateEdge(edge, nodes);
            if (!((List<String>) edgeResult.get("errors"))).isEmpty()) {
                errors.addAll((List<String>) edgeResult.get("errors"));
            }
        }

        result.put("valid", errors.isEmpty());
        result.put("errors", errors);
        return result;
    }

    @Override
    public Map<String, Object> validateNode(Map<String, Object> node) {
        Map<String, Object> result = new HashMap<>();
        List<String> errors = new ArrayList<>();

        String type = (String) node.get("type");
        String name = (String) node.get("name");

        if (name == null || name.trim().isEmpty()) {
            errors.add("节点名称不能为空");
        }

        if ("approval".equals(type)) {
            List<Long> approvers = (List<Long>) node.get("approvers");
            String approverResolver = (String) node.get("approverResolver");
            if ((approvers == null || approvers.isEmpty()) && (approverResolver == null || approverResolver.isEmpty())) {
                errors.add("审批节点必须指定审批人或审批人解析器");
            }
        } else if ("condition".equals(type)) {
            String condition = (String) node.get("condition");
            if (condition == null || condition.trim().isEmpty()) {
                errors.add("条件节点必须指定条件表达式");
            }
        }

        result.put("valid", errors.isEmpty());
        result.put("errors", errors);
        return result;
    }

    @Override
    public Map<String, Object> validateEdge(Map<String, Object> edge, List<Map<String, Object>> nodes) {
        Map<String, Object> result = new HashMap<>();
        List<String> errors = new ArrayList<>();

        String source = (String) edge.get("source");
        String target = (String) edge.get("target");

        if (source == null || source.isEmpty()) {
            errors.add("连线源节点不能为空");
        }
        if (target == null || target.isEmpty()) {
            errors.add("连线目标节点不能为空");
        }

        Set<String> nodeIds = new HashSet<>();
        for (Map<String, Object> node : nodes) {
            nodeIds.add((String) node.get("id"));
        }

        if (source != null && !nodeIds.contains(source)) {
            errors.add("源节点不存在: " + source);
        }
        if (target != null && !nodeIds.contains(target)) {
            errors.add("目标节点不存在: " + target);
        }

        if (source != null && target != null && source.equals(target)) {
            errors.add("不能连接到自己");
        }

        result.put("valid", errors.isEmpty());
        result.put("errors", errors);
        return result;
    }

    @Override
    public Map<String, Object> generateFlowDefinition(Long designId) {
        FlowDesign design = flowDesignMapper.selectById(designId);
        if (design == null) {
            throw new BusinessException("设计不存在");
        }

        Map<String, Object> designData = JsonUtil.fromJson(design.getDesignData(), Map.class);

        List<Map<String, Object>> nodes = (List<Map<String, Object>>) designData.get("nodes");
        List<Map<String, Object>> edges = (List<Map<String, Object>>) designData.get("edges");

        buildOutgoingEdges(nodes, edges);

        Map<String, Object> flowDefinition = new HashMap<>();
        flowDefinition.put("nodes", nodes);
        flowDefinition.put("edges", edges);
        flowDefinition.put("designId", designId);
        flowDefinition.put("designCode", design.getDesignCode());
        flowDefinition.put("designName", design.getDesignName());

        return flowDefinition;
    }

    private void buildOutgoingEdges(List<Map<String, Object>> nodes, List<Map<String, Object>> edges) {
        Map<String, List<Map<String, Object>>> outgoingMap = new HashMap<>();
        for (Map<String, Object> edge : edges) {
            String source = (String) edge.get("source");
            outgoingMap.computeIfAbsent(source, k -> new ArrayList<>()).add(edge);
        }
        for (Map<String, Object> node : nodes) {
            String nodeId = (String) node.get("id");
            node.put("outgoing", outgoingMap.getOrDefault(nodeId, Collections.emptyList()));
        }
    }

    @Override
    public Map<String, Object> getDesignPreview(Long id) {
        FlowDesign design = flowDesignMapper.selectById(id);
        if (design == null) {
            throw new BusinessException("设计不存在");
        }

        Map<String, Object> result = new HashMap<>();
        result.put("design", design);
        result.put("flowDefinition", generateFlowDefinition(id));
        return result;
    }

    @Override
    @Transactional
    public boolean copyDesign(Long id, String newDesignCode, String newDesignName) {
        FlowDesign source = flowDesignMapper.selectById(id);
        if (source == null) {
            throw new BusinessException("源设计不存在");
        }

        FlowDesign copy = new FlowDesign();
        copy.setDesignCode(newDesignCode);
        copy.setDesignName(newDesignName);
        copy.setFlowType(source.getFlowType());
        copy.setDescription(source.getDescription());
        copy.setNodeDefinitions(source.getNodeDefinitions());
        copy.setEdgeDefinitions(source.getEdgeDefinitions());
        copy.setDesignData(source.getDesignData());
        copy.setStatus("draft");
        copy.setVersion(1);

        return flowDesignMapper.insert(copy) > 0;
    }

    @Override
    public List<Map<String, Object>> getNodeTemplates() {
        List<Map<String, Object>> templates = new ArrayList<>();

        Map<String, Object> startNode = new HashMap<>();
        startNode.put("type", "start");
        startNode.put("name", "开始");
        startNode.put("icon", "start");
        startNode.put("description", "流程开始节点");
        templates.add(startNode);

        Map<String, Object> endNode = new HashMap<>();
        endNode.put("type", "end");
        endNode.put("name", "结束");
        endNode.put("icon", "end");
        endNode.put("description", "流程结束节点");
        templates.add(endNode);

        Map<String, Object> approvalNode = new HashMap<>();
        approvalNode.put("type", "approval");
        approvalNode.put("name", "审批");
        approvalNode.put("icon", "approval");
        approvalNode.put("description", "人工审批节点");
        approvalNode.put("configFields", Arrays.asList("approvers", "approvalType", "approverResolver"));
        templates.add(approvalNode);

        Map<String, Object> conditionNode = new HashMap<>();
        conditionNode.put("type", "condition");
        conditionNode.put("name", "条件");
        conditionNode.put("icon", "condition");
        conditionNode.put("description", "条件分支节点");
        conditionNode.put("configFields", Arrays.asList("condition"));
        templates.add(conditionNode);

        Map<String, Object> parallelNode = new HashMap<>();
        parallelNode.put("type", "parallel");
        parallelNode.put("name", "并行");
        parallelNode.put("icon", "parallel");
        parallelNode.put("description", "并行网关节点");
        templates.add(parallelNode);

        Map<String, Object> taskNode = new HashMap<>();
        taskNode.put("type", "task");
        taskNode.put("name", "任务");
        taskNode.put("icon", "task");
        taskNode.put("description", "自动任务节点");
        taskNode.put("configFields", Arrays.asList("taskType", "taskParams"));
        templates.add(taskNode);

        return templates;
    }
}
