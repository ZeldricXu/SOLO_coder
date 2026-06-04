package com.flowplatform.common;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
public class ProcessEngine {

    @Getter
    private final List<String> errors = new ArrayList<>();

    public record ProcessExecutionResult(
            String status,
            List<String> currentNodes,
            List<String> executedNodes,
            Map<String, String> nodeResults,
            Map<String, List<Long>> nodeAssignees
    ) {}

    public ProcessExecutionResult executeProcess(JSONObject processData, Map<String, Object> formData,
                                                 Map<String, ApprovalAction> actions) {
        errors.clear();

        JSONArray nodes = processData.getJSONArray("nodes");
        JSONArray edges = processData.getJSONArray("edges");

        if (nodes == null || edges == null) {
            errors.add("流程定义不完整");
            return new ProcessExecutionResult("ERROR", new ArrayList<>(), new ArrayList<>(), new HashMap<>(), new HashMap<>());
        }

        if (detectCycle(nodes, edges)) {
            errors.add("流程定义存在循环引用");
            return new ProcessExecutionResult("ERROR", new ArrayList<>(), new ArrayList<>(), new HashMap<>(), new HashMap<>());
        }

        return simulateExecution(nodes, edges, formData, actions);
    }

    public boolean detectCycle(JSONArray nodes, JSONArray edges) {
        Map<String, List<String>> graph = buildGraph(nodes, edges);
        Set<String> visited = new HashSet<>();
        Set<String> inPath = new HashSet<>();

        for (int i = 0; i < nodes.size(); i++) {
            String nodeId = nodes.getJSONObject(i).getString("id");
            if (hasCycle(nodeId, graph, visited, inPath)) {
                return true;
            }
        }
        return false;
    }

    private Map<String, List<String>> buildGraph(JSONArray nodes, JSONArray edges) {
        Map<String, List<String>> graph = new HashMap<>();
        for (int i = 0; i < nodes.size(); i++) {
            graph.put(nodes.getJSONObject(i).getString("id"), new ArrayList<>());
        }
        for (int i = 0; i < edges.size(); i++) {
            JSONObject edge = edges.getJSONObject(i);
            String source = edge.getString("source");
            String target = edge.getString("target");
            if (graph.containsKey(source)) {
                graph.get(source).add(target);
            }
        }
        return graph;
    }

    private boolean hasCycle(String nodeId, Map<String, List<String>> graph, Set<String> visited, Set<String> inPath) {
        if (inPath.contains(nodeId)) return true;
        if (visited.contains(nodeId)) return false;

        visited.add(nodeId);
        inPath.add(nodeId);

        for (String neighbor : graph.getOrDefault(nodeId, Collections.emptyList())) {
            if (hasCycle(neighbor, graph, visited, inPath)) {
                return true;
            }
        }
        inPath.remove(nodeId);
        return false;
    }

    private ProcessExecutionResult simulateExecution(JSONArray nodes, JSONArray edges,
                                                     Map<String, Object> formData,
                                                     Map<String, ApprovalAction> actions) {
        Map<String, String> nodeResults = new HashMap<>();
        Map<String, List<Long>> nodeAssignees = new HashMap<>();
        List<String> executedNodes = new ArrayList<>();
        List<String> currentNodes = new ArrayList<>();

        Map<String, JSONObject> nodeMap = new HashMap<>();
        for (int i = 0; i < nodes.size(); i++) {
            JSONObject node = nodes.getJSONObject(i);
            nodeMap.put(node.getString("id"), node);
        }

        Map<String, List<JSONObject>> outgoingEdges = new HashMap<>();
        for (int i = 0; i < edges.size(); i++) {
            JSONObject edge = edges.getJSONObject(i);
            outgoingEdges.computeIfAbsent(edge.getString("source"), k -> new ArrayList<>()).add(edge);
        }

        String currentNodeId = findStartNode(nodes);
        String overallStatus = "RUNNING";

        while (currentNodeId != null) {
            JSONObject currentNode = nodeMap.get(currentNodeId);
            if (currentNode == null) {
                errors.add("节点不存在: " + currentNodeId);
                break;
            }

            String nodeType = currentNode.getString("type");
            executedNodes.add(currentNodeId);

            switch (nodeType) {
                case "start":
                    currentNodeId = getNextNode(currentNodeId, outgoingEdges, formData, null);
                    break;
                case "approval": {
                    Long assignee = currentNode.getLong("assigneeId");
                    if (assignee == null) {
                        JSONArray assignees = currentNode.getJSONArray("assigneeIds");
                        if (assignees != null && !assignees.isEmpty()) {
                            assignee = assignees.getLong(0);
                        }
                    }
                    if (assignee == null) {
                        errors.add("审批节点[" + currentNodeId + "]未指定审批人");
                        overallStatus = "ERROR";
                        currentNodeId = null;
                        break;
                    }
                    nodeAssignees.put(currentNodeId, Collections.singletonList(assignee));

                    ApprovalAction action = actions.get(currentNodeId);
                    if (action == null) {
                        currentNodes.add(currentNodeId);
                        currentNodeId = null;
                    } else if (action.isApproved()) {
                        nodeResults.put(currentNodeId, "APPROVED");
                        currentNodeId = getNextNode(currentNodeId, outgoingEdges, formData, null);
                    } else {
                        nodeResults.put(currentNodeId, "REJECTED");
                        overallStatus = "REJECTED";
                        currentNodeId = null;
                    }
                    break;
                }
                case "condition": {
                    String nextNode = evaluateConditionEdges(currentNodeId, outgoingEdges, formData);
                    if (nextNode == null) {
                        errors.add("条件节点[" + currentNodeId + "]没有匹配的分支");
                        overallStatus = "ERROR";
                        currentNodeId = null;
                    } else {
                        currentNodeId = nextNode;
                    }
                    break;
                }
                case "parallel": {
                    String signType = currentNode.getString("signType");
                    JSONArray parallelAssignees = currentNode.getJSONArray("assignees");
                    if (parallelAssignees != null) {
                        List<Long> assigneeList = new ArrayList<>();
                        for (int i = 0; i < parallelAssignees.size(); i++) {
                            assigneeList.add(parallelAssignees.getLong(i));
                        }
                        nodeAssignees.put(currentNodeId, assigneeList);
                    }

                    int approveCount = 0;
                    int rejectCount = 0;
                    int total = parallelAssignees != null ? parallelAssignees.size() : 1;
                    boolean allResponded = true;

                    for (int i = 0; i < total; i++) {
                        String actionKey = currentNodeId + "_" + i;
                        ApprovalAction action = actions.get(actionKey);
                        if (action == null) {
                            allResponded = false;
                            if (!currentNodes.contains(currentNodeId)) {
                                currentNodes.add(currentNodeId);
                            }
                        } else if (action.isApproved()) {
                            approveCount++;
                        } else {
                            rejectCount++;
                        }
                    }

                    if (allResponded) {
                        if ("all".equals(signType)) {
                            if (approveCount == total) {
                                nodeResults.put(currentNodeId, "APPROVED");
                                currentNodeId = getNextNode(currentNodeId, outgoingEdges, formData, null);
                            } else {
                                nodeResults.put(currentNodeId, "REJECTED");
                                overallStatus = "REJECTED";
                                currentNodeId = null;
                            }
                        } else {
                            if (approveCount >= 1) {
                                nodeResults.put(currentNodeId, "APPROVED");
                                currentNodeId = getNextNode(currentNodeId, outgoingEdges, formData, null);
                            } else if (rejectCount == total) {
                                nodeResults.put(currentNodeId, "REJECTED");
                                overallStatus = "REJECTED";
                                currentNodeId = null;
                            } else {
                                currentNodes.add(currentNodeId);
                                currentNodeId = null;
                            }
                        }
                    } else {
                        currentNodeId = null;
                    }
                    break;
                }
                case "end":
                    nodeResults.put(currentNodeId, "COMPLETED");
                    overallStatus = "COMPLETED";
                    currentNodeId = null;
                    break;
                case "timeout":
                    currentNodeId = getNextNode(currentNodeId, outgoingEdges, formData, null);
                    break;
                default:
                    currentNodeId = getNextNode(currentNodeId, outgoingEdges, formData, null);
            }
        }

        return new ProcessExecutionResult(overallStatus, currentNodes, executedNodes, nodeResults, nodeAssignees);
    }

    private String findStartNode(JSONArray nodes) {
        for (int i = 0; i < nodes.size(); i++) {
            JSONObject node = nodes.getJSONObject(i);
            if ("start".equals(node.getString("type"))) {
                return node.getString("id");
            }
        }
        return null;
    }

    private String getNextNode(String currentNodeId, Map<String, List<JSONObject>> outgoingEdges,
                               Map<String, Object> formData, String condition) {
        List<JSONObject> edges = outgoingEdges.get(currentNodeId);
        if (edges == null || edges.isEmpty()) return null;
        return edges.get(0).getString("target");
    }

    private String evaluateConditionEdges(String currentNodeId, Map<String, List<JSONObject>> outgoingEdges,
                                          Map<String, Object> formData) {
        List<JSONObject> edges = outgoingEdges.get(currentNodeId);
        if (edges == null) return null;

        for (JSONObject edge : edges) {
            String condition = edge.getString("condition");
            if (condition == null || condition.isEmpty()) {
                return edge.getString("target");
            }
            if (evaluateCondition(condition, formData)) {
                return edge.getString("target");
            }
        }
        return null;
    }

    public boolean evaluateCondition(String condition, Map<String, Object> formData) {
        return evaluateCondition(condition, formData, null);
    }

    public boolean evaluateCondition(String condition, Map<String, Object> formData, JSONObject formSchema) {
        try {
            Pattern pattern = Pattern.compile("\\$\\{([^}]+)\\}");
            Matcher m = pattern.matcher(condition);

            Map<String, String> replacements = new HashMap<>();
            while (m.find()) {
                String key = m.group(1);
                Object rawValue = formData.get(key);
                Object convertedValue = convertValueByType(key, rawValue, formSchema);

                String replacement;
                if (convertedValue == null) {
                    replacement = "null";
                } else if (convertedValue instanceof Number) {
                    replacement = convertedValue.toString();
                } else if (convertedValue instanceof LocalDateTime) {
                    replacement = "'" + convertedValue.toString() + "'";
                } else {
                    String strVal = convertedValue.toString();
                    if (strVal.startsWith("'") && strVal.endsWith("'")) {
                        replacement = strVal;
                    } else if (strVal.startsWith("\"") && strVal.endsWith("\"")) {
                        replacement = strVal;
                    } else {
                        replacement = "'" + strVal + "'";
                    }
                }
                replacements.put("${" + key + "}", replacement);
            }

            String expr = condition;
            for (Map.Entry<String, String> entry : replacements.entrySet()) {
                expr = expr.replace(entry.getKey(), entry.getValue());
            }

            List<String> subConditions = new ArrayList<>();
            List<String> operators = new ArrayList<>();

            String tempExpr = expr;
            while (true) {
                int andIndex = tempExpr.indexOf(" AND ");
                int orIndex = tempExpr.indexOf(" OR ");
                int andIndexLower = tempExpr.indexOf(" and ");
                int orIndexLower = tempExpr.indexOf(" or ");

                List<Integer> indices = new ArrayList<>();
                if (andIndex != -1) indices.add(andIndex);
                if (orIndex != -1) indices.add(orIndex);
                if (andIndexLower != -1) indices.add(andIndexLower);
                if (orIndexLower != -1) indices.add(orIndexLower);

                if (indices.isEmpty()) {
                    subConditions.add(tempExpr.trim());
                    break;
                }

                int minIndex = Collections.min(indices);
                String operator;
                if (minIndex == andIndex || minIndex == andIndexLower) {
                    operator = "AND";
                } else {
                    operator = "OR";
                }

                subConditions.add(tempExpr.substring(0, minIndex).trim());
                operators.add(operator);

                int operatorLength = (minIndex == andIndex || minIndex == andIndexLower) ? 5 : 4;
                tempExpr = tempExpr.substring(minIndex + operatorLength).trim();
            }

            if (subConditions.size() == 1) {
                return evaluateBooleanExpression(subConditions.get(0));
            }

            boolean result = evaluateBooleanExpression(subConditions.get(0));
            for (int i = 0; i < operators.size(); i++) {
                boolean nextResult = evaluateBooleanExpression(subConditions.get(i + 1));
                String op = operators.get(i);
                if ("AND".equals(op)) {
                    result = result && nextResult;
                } else {
                    result = result || nextResult;
                }
            }

            return result;
        } catch (Exception e) {
            log.warn("Condition evaluate failed: {}", condition, e);
            return false;
        }
    }

    private String stripQuotes(String str) {
        if (str == null) return null;
        String trimmed = str.trim();
        if ((trimmed.startsWith("'") && trimmed.endsWith("'")) ||
            (trimmed.startsWith("\"") && trimmed.endsWith("\""))) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }

    private boolean evaluateBooleanExpression(String expr) {
        expr = expr.trim();

        if (expr.contains(">=")) {
            String[] parts = expr.split(">=", 2);
            String leftStr = parts[0].trim();
            String rightStr = parts[1].trim();
            Double leftNum = parseNumber(leftStr);
            Double rightNum = parseNumber(rightStr);
            if (leftNum != null && rightNum != null) {
                return leftNum >= rightNum;
            }
            log.warn("Non-numeric comparison for >=, falling back to string comparison: left={}, right={}", leftStr, rightStr);
            return stripQuotes(leftStr).compareTo(stripQuotes(rightStr)) >= 0;
        }
        if (expr.contains("<=")) {
            String[] parts = expr.split("<=", 2);
            String leftStr = parts[0].trim();
            String rightStr = parts[1].trim();
            Double leftNum = parseNumber(leftStr);
            Double rightNum = parseNumber(rightStr);
            if (leftNum != null && rightNum != null) {
                return leftNum <= rightNum;
            }
            log.warn("Non-numeric comparison for <=, falling back to string comparison: left={}, right={}", leftStr, rightStr);
            return stripQuotes(leftStr).compareTo(stripQuotes(rightStr)) <= 0;
        }
        if (expr.contains("!=")) {
            String[] parts = expr.split("!=", 2);
            String leftStr = parts[0].trim();
            String rightStr = parts[1].trim();
            Double leftNum = parseNumber(leftStr);
            Double rightNum = parseNumber(rightStr);
            if (leftNum != null && rightNum != null) {
                return !leftNum.equals(rightNum);
            }
            return !Objects.equals(stripQuotes(leftStr), stripQuotes(rightStr));
        }
        if (expr.contains(">")) {
            String[] parts = expr.split(">", 2);
            String leftStr = parts[0].trim();
            String rightStr = parts[1].trim();
            Double leftNum = parseNumber(leftStr);
            Double rightNum = parseNumber(rightStr);
            if (leftNum != null && rightNum != null) {
                return leftNum > rightNum;
            }
            log.warn("Non-numeric comparison for >, falling back to string comparison: left={}, right={}", leftStr, rightStr);
            return stripQuotes(leftStr).compareTo(stripQuotes(rightStr)) > 0;
        }
        if (expr.contains("<")) {
            String[] parts = expr.split("<", 2);
            String leftStr = parts[0].trim();
            String rightStr = parts[1].trim();
            Double leftNum = parseNumber(leftStr);
            Double rightNum = parseNumber(rightStr);
            if (leftNum != null && rightNum != null) {
                return leftNum < rightNum;
            }
            log.warn("Non-numeric comparison for <, falling back to string comparison: left={}, right={}", leftStr, rightStr);
            return stripQuotes(leftStr).compareTo(stripQuotes(rightStr)) < 0;
        }
        if (expr.contains("==")) {
            String[] parts = expr.split("==", 2);
            String leftStr = parts[0].trim();
            String rightStr = parts[1].trim();
            Double leftNum = parseNumber(leftStr);
            Double rightNum = parseNumber(rightStr);
            if (leftNum != null && rightNum != null) {
                return leftNum.equals(rightNum);
            }
            return Objects.equals(stripQuotes(leftStr), stripQuotes(rightStr));
        }

        return false;
    }

    private Double parseNumber(String str) {
        try {
            return Double.parseDouble(str.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Object convertValueByType(String fieldKey, Object rawValue, JSONObject formSchema) {
        if (rawValue == null) {
            return null;
        }

        String fieldType = null;
        if (formSchema != null) {
            JSONArray fields = formSchema.getJSONArray("fields");
            if (fields != null) {
                for (int i = 0; i < fields.size(); i++) {
                    JSONObject field = fields.getJSONObject(i);
                    if (fieldKey.equals(field.getString("key"))) {
                        fieldType = field.getString("type");
                        break;
                    }
                }
            }
        }

        if (fieldType == null) {
            log.warn("Field type not found for key: {}, defaulting to string comparison", fieldKey);
            return rawValue.toString();
        }

        String valueStr = rawValue.toString();

        try {
            switch (fieldType) {
                case "number":
                case "amount":
                    Double numVal = parseNumber(valueStr);
                    if (numVal == null) {
                        log.warn("Failed to parse number for field: {}, value: {}, using string comparison", fieldKey, valueStr);
                        return valueStr;
                    }
                    return numVal;
                case "date":
                case "datetime":
                case "dateRange":
                    try {
                        return LocalDateTime.parse(valueStr, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
                    } catch (DateTimeParseException e1) {
                        try {
                            return LocalDateTime.parse(valueStr, DateTimeFormatter.ISO_LOCAL_DATE);
                        } catch (DateTimeParseException e2) {
                            log.warn("Failed to parse date for field: {}, value: {}, using string comparison", fieldKey, valueStr);
                            return valueStr;
                        }
                    }
                case "select":
                case "radio":
                case "text":
                case "textarea":
                default:
                    return valueStr;
            }
        } catch (Exception e) {
            log.warn("Type conversion failed for field: {}, type: {}, value: {}, using string comparison",
                    fieldKey, fieldType, valueStr, e);
            return valueStr;
        }
    }

    public static class ApprovalAction {
        private final boolean approved;
        private final String comment;

        public ApprovalAction(boolean approved, String comment) {
            this.approved = approved;
            this.comment = comment;
        }

        public static ApprovalAction approve(String comment) {
            return new ApprovalAction(true, comment);
        }

        public static ApprovalAction reject(String comment) {
            return new ApprovalAction(false, comment);
        }

        public boolean isApproved() { return approved; }
        public String getComment() { return comment; }
    }

    public boolean validateAssigneesExist(JSONObject processData, Set<Long> validUserIds) {
        JSONArray nodes = processData.getJSONArray("nodes");
        if (nodes == null) return true;

        for (int i = 0; i < nodes.size(); i++) {
            JSONObject node = nodes.getJSONObject(i);
            String type = node.getString("type");
            if ("approval".equals(type) || "parallel".equals(type)) {
                JSONArray assignees = node.getJSONArray("assignees");
                if (assignees != null) {
                    for (int j = 0; j < assignees.size(); j++) {
                        Long userId = assignees.getLong(j);
                        if (userId != null && !validUserIds.contains(userId)) {
                            errors.add("审批节点[" + node.getString("name") + "]的审批人不存在: " + userId);
                            return false;
                        }
                    }
                }
                Long singleAssignee = node.getLong("assigneeId");
                if (singleAssignee != null && !validUserIds.contains(singleAssignee)) {
                    errors.add("审批节点[" + node.getString("name") + "]的审批人不存在: " + singleAssignee);
                    return false;
                }
            }
        }
        return true;
    }
}
