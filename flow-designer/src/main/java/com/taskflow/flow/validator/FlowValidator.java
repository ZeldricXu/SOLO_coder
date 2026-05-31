package com.taskflow.flow.validator;

import com.taskflow.flow.model.*;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

@Component
public class FlowValidator {

    public ValidationResult validate(FlowDefinition flow) {
        List<ValidationResult.ValidationError> errors = new ArrayList<>();
        List<ValidationResult.ValidationWarning> warnings = new ArrayList<>();

        validateBasicStructure(flow, errors);
        validateNodes(flow, errors, warnings);
        validateEdges(flow, errors, warnings);
        validateConnectivity(flow, errors);
        validateNoCycles(flow, errors);

        return ValidationResult.builder()
                .valid(errors.isEmpty())
                .errors(errors)
                .warnings(warnings)
                .build();
    }

    private void validateBasicStructure(FlowDefinition flow, List<ValidationResult.ValidationError> errors) {
        if (flow.getName() == null || flow.getName().trim().isEmpty()) {
            errors.add(ValidationResult.ValidationError.builder()
                    .code("EMPTY_NAME")
                    .message("流程名称不能为空")
                    .build());
        }

        if (flow.getNodes() == null || flow.getNodes().isEmpty()) {
            errors.add(ValidationResult.ValidationError.builder()
                    .code("NO_NODES")
                    .message("流程必须包含至少一个节点")
                    .build());
        }
    }

    private void validateNodes(FlowDefinition flow, List<ValidationResult.ValidationError> errors,
                               List<ValidationResult.ValidationWarning> warnings) {
        if (flow.getNodes() == null) return;

        Set<String> nodeIds = new HashSet<>();
        boolean hasStart = false;
        boolean hasEnd = false;

        for (FlowNode node : flow.getNodes()) {
            if (node.getNodeId() == null || node.getNodeId().trim().isEmpty()) {
                errors.add(ValidationResult.ValidationError.builder()
                        .code("EMPTY_NODE_ID")
                        .message("节点ID不能为空")
                        .build());
                continue;
            }

            if (nodeIds.contains(node.getNodeId())) {
                errors.add(ValidationResult.ValidationError.builder()
                        .code("DUPLICATE_NODE_ID")
                        .message("节点ID重复: " + node.getNodeId())
                        .nodeId(node.getNodeId())
                        .build());
            }
            nodeIds.add(node.getNodeId());

            NodeType nodeType = NodeType.fromCode(node.getNodeType());
            if (nodeType == null) {
                errors.add(ValidationResult.ValidationError.builder()
                        .code("INVALID_NODE_TYPE")
                        .message("无效的节点类型: " + node.getNodeType())
                        .nodeId(node.getNodeId())
                        .build());
            } else {
                if (nodeType == NodeType.START) hasStart = true;
                if (nodeType == NodeType.END) hasEnd = true;
            }

            if (node.getPosition() == null || node.getPosition().isEmpty()) {
                warnings.add(ValidationResult.ValidationWarning.builder()
                        .code("NO_POSITION")
                        .message("节点未设置位置: " + node.getNodeId())
                        .nodeId(node.getNodeId())
                        .build());
            }
        }

        if (!hasStart) {
            errors.add(ValidationResult.ValidationError.builder()
                    .code("NO_START_NODE")
                    .message("流程必须包含开始节点")
                    .build());
        }

        if (!hasEnd) {
            errors.add(ValidationResult.ValidationError.builder()
                    .code("NO_END_NODE")
                    .message("流程必须包含结束节点")
                    .build());
        }
    }

    private void validateEdges(FlowDefinition flow, List<ValidationResult.ValidationError> errors,
                               List<ValidationResult.ValidationWarning> warnings) {
        if (flow.getEdges() == null) return;

        Set<String> nodeIds = flow.getNodes().stream()
                .map(FlowNode::getNodeId)
                .collect(Collectors.toSet());

        Map<String, Integer> outgoingEdges = new HashMap<>();
        Map<String, Integer> incomingEdges = new HashMap<>();

        for (FlowEdge edge : flow.getEdges()) {
            if (edge.getSourceNodeId() == null || edge.getTargetNodeId() == null) {
                errors.add(ValidationResult.ValidationError.builder()
                        .code("INVALID_EDGE")
                        .message("连线必须指定源节点和目标节点")
                        .edgeId(edge.getEdgeId())
                        .build());
                continue;
            }

            if (!nodeIds.contains(edge.getSourceNodeId())) {
                errors.add(ValidationResult.ValidationError.builder()
                        .code("INVALID_SOURCE_NODE")
                        .message("源节点不存在: " + edge.getSourceNodeId())
                        .edgeId(edge.getEdgeId())
                        .build());
            }

            if (!nodeIds.contains(edge.getTargetNodeId())) {
                errors.add(ValidationResult.ValidationError.builder()
                        .code("INVALID_TARGET_NODE")
                        .message("目标节点不存在: " + edge.getTargetNodeId())
                        .edgeId(edge.getEdgeId())
                        .build());
            }

            FlowNode sourceNode = flow.getNodes().stream()
                    .filter(n -> n.getNodeId().equals(edge.getSourceNodeId()))
                    .findFirst().orElse(null);
            FlowNode targetNode = flow.getNodes().stream()
                    .filter(n -> n.getNodeId().equals(edge.getTargetNodeId()))
                    .findFirst().orElse(null);

            if (sourceNode != null) {
                NodeType sourceType = NodeType.fromCode(sourceNode.getNodeType());
                if (sourceType != null && !sourceType.isHasOutput()) {
                    errors.add(ValidationResult.ValidationError.builder()
                            .code("NODE_NO_OUTPUT")
                            .message(sourceType.getName() + "不能有出边")
                            .edgeId(edge.getEdgeId())
                            .nodeId(sourceNode.getNodeId())
                            .build());
                }
                outgoingEdges.merge(sourceNode.getNodeId(), 1, Integer::sum);
            }

            if (targetNode != null) {
                NodeType targetType = NodeType.fromCode(targetNode.getNodeType());
                if (targetType != null && !targetType.isHasInput()) {
                    errors.add(ValidationResult.ValidationError.builder()
                            .code("NODE_NO_INPUT")
                            .message(targetType.getName() + "不能有入边")
                            .edgeId(edge.getEdgeId())
                            .nodeId(targetNode.getNodeId())
                            .build());
                }
                incomingEdges.merge(targetNode.getNodeId(), 1, Integer::sum);
            }
        }

        for (FlowNode node : flow.getNodes()) {
            NodeType nodeType = NodeType.fromCode(node.getNodeType());
            if (nodeType == null) continue;

            int outCount = outgoingEdges.getOrDefault(node.getNodeId(), 0);
            int inCount = incomingEdges.getOrDefault(node.getNodeId(), 0);

            if (nodeType.isHasOutput() && outCount == 0 && nodeType != NodeType.END) {
                warnings.add(ValidationResult.ValidationWarning.builder()
                        .code("NODE_NO_OUTGOING")
                        .message(node.getName() + "没有出边")
                        .nodeId(node.getNodeId())
                        .build());
            }

            if (nodeType.isHasInput() && inCount == 0 && nodeType != NodeType.START) {
                warnings.add(ValidationResult.ValidationWarning.builder()
                        .code("NODE_NO_INCOMING")
                        .message(node.getName() + "没有入边")
                        .nodeId(node.getNodeId())
                        .build());
            }
        }
    }

    private void validateConnectivity(FlowDefinition flow, List<ValidationResult.ValidationError> errors) {
        if (flow.getNodes() == null || flow.getEdges() == null) return;

        Map<String, List<String>> adjacencyList = new HashMap<>();
        for (FlowNode node : flow.getNodes()) {
            adjacencyList.put(node.getNodeId(), new ArrayList<>());
        }
        for (FlowEdge edge : flow.getEdges()) {
            adjacencyList.computeIfAbsent(edge.getSourceNodeId(), k -> new ArrayList<>())
                    .add(edge.getTargetNodeId());
        }

        Set<String> startNodes = flow.getNodes().stream()
                .filter(n -> NodeType.START.getCode().equals(n.getNodeType()))
                .map(FlowNode::getNodeId)
                .collect(Collectors.toSet());

        if (startNodes.isEmpty()) return;

        Set<String> reachable = new HashSet<>();
        for (String startNode : startNodes) {
            dfs(startNode, adjacencyList, reachable);
        }

        for (FlowNode node : flow.getNodes()) {
            if (!reachable.contains(node.getNodeId()) && !NodeType.START.getCode().equals(node.getNodeType())) {
                errors.add(ValidationResult.ValidationError.builder()
                        .code("UNREACHABLE_NODE")
                        .message("节点不可达: " + node.getName())
                        .nodeId(node.getNodeId())
                        .build());
            }
        }
    }

    private void dfs(String node, Map<String, List<String>> adjacencyList, Set<String> visited) {
        if (visited.contains(node)) return;
        visited.add(node);
        for (String neighbor : adjacencyList.getOrDefault(node, Collections.emptyList())) {
            dfs(neighbor, adjacencyList, visited);
        }
    }

    private void validateNoCycles(FlowDefinition flow, List<ValidationResult.ValidationError> errors) {
        if (flow.getNodes() == null || flow.getEdges() == null) return;

        Map<String, List<String>> adjacencyList = new HashMap<>();
        Map<String, Integer> inDegree = new HashMap<>();

        for (FlowNode node : flow.getNodes()) {
            adjacencyList.put(node.getNodeId(), new ArrayList<>());
            inDegree.put(node.getNodeId(), 0);
        }

        for (FlowEdge edge : flow.getEdges()) {
            adjacencyList.get(edge.getSourceNodeId()).add(edge.getTargetNodeId());
            inDegree.merge(edge.getTargetNodeId(), 1, Integer::sum);
        }

        Queue<String> queue = new LinkedList<>();
        for (Map.Entry<String, Integer> entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) {
                queue.add(entry.getKey());
            }
        }

        int processed = 0;
        while (!queue.isEmpty()) {
            String node = queue.poll();
            processed++;
            for (String neighbor : adjacencyList.getOrDefault(node, Collections.emptyList())) {
                inDegree.merge(neighbor, -1, Integer::sum);
                if (inDegree.get(neighbor) == 0) {
                    queue.add(neighbor);
                }
            }
        }

        if (processed != flow.getNodes().size()) {
            errors.add(ValidationResult.ValidationError.builder()
                    .code("CYCLE_DETECTED")
                    .message("流程中存在循环")
                    .build());
        }
    }
}
