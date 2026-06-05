package com.company.dbstudio.sql.service;

import com.company.dbstudio.connection.model.ConnectionType;
import com.company.dbstudio.sql.model.ExecutionPlan;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ExecutionPlanParser {
    private static final Pattern COST_PATTERN = Pattern.compile("cost=([\\d.]+)");
    private static final Pattern ROWS_PATTERN = Pattern.compile("rows=([\\d.]+)");
    private static final Pattern WIDTH_PATTERN = Pattern.compile("width=(\\d+)");

    public ExecutionPlan parse(List<String[]> planRows, ConnectionType type) {
        return switch (type) {
            case MYSQL -> parseMySQLPlan(planRows);
            case POSTGRESQL -> parsePostgreSQLPlan(planRows);
            case ORACLE -> parseOraclePlan(planRows);
            case SQL_SERVER -> parseSQLServerPlan(planRows);
            default -> parseGenericPlan(planRows);
        };
    }

    private ExecutionPlan parseMySQLPlan(List<String[]> planRows) {
        if (planRows == null || planRows.isEmpty()) {
            return createEmptyPlan();
        }

        Map<Integer, ExecutionPlan> nodeMap = new LinkedHashMap<>();
        ExecutionPlan root = null;

        for (String[] row : planRows) {
            if (row.length < 10) continue;

            ExecutionPlan node = new ExecutionPlan();
            node.setId(row[0]);
            node.setOperation(row[1] != null ? row[1] : "");
            node.setObjectName(row[2] != null ? row[2] : "");
            
            try {
                if (row[3] != null && !row[3].isEmpty()) {
                    node.setRows(Long.parseLong(row[3]));
                }
                if (row[9] != null && !row[9].isEmpty()) {
                    node.setBytes(Long.parseLong(row[9]));
                }
                if (row[5] != null && !row[5].isEmpty()) {
                    node.setCost(Double.parseDouble(row[5]));
                }
            } catch (NumberFormatException ignored) {
            }

            node.setFilter(row[7]);
            node.setPredicate(row[8]);
            node.setExtra(row[10] != null ? row[10] : "");

            try {
                int id = Integer.parseInt(row[0]);
                nodeMap.put(id, node);
                if (id == 1) {
                    root = node;
                }
            } catch (NumberFormatException ignored) {
            }
        }

        buildTreeFromMySQL(nodeMap);
        return root != null ? root : createEmptyPlan();
    }

    private void buildTreeFromMySQL(Map<Integer, ExecutionPlan> nodeMap) {
        List<Integer> ids = new ArrayList<>(nodeMap.keySet());
        Collections.sort(ids);

        for (int i = 0; i < ids.size(); i++) {
            ExecutionPlan current = nodeMap.get(ids.get(i));
            if (current.getOperation() != null && current.getOperation().contains("DERIVED")) {
                if (i > 0) {
                    nodeMap.get(ids.get(i - 1)).addChild(current);
                }
            } else if (current.getOperation() != null && current.getOperation().contains("SUBQUERY")) {
                if (i > 0) {
                    nodeMap.get(ids.get(0)).addChild(current);
                }
            }
        }

        if (ids.size() > 1) {
            ExecutionPlan root = nodeMap.get(ids.get(0));
            for (int i = 1; i < ids.size(); i++) {
                ExecutionPlan node = nodeMap.get(ids.get(i));
                if (node.getParent() == null) {
                    root.addChild(node);
                }
            }
        }
    }

    private ExecutionPlan parsePostgreSQLPlan(List<String[]> planRows) {
        if (planRows == null || planRows.isEmpty()) {
            return createEmptyPlan();
        }

        ExecutionPlan root = null;
        Map<Integer, ExecutionPlan> nodeMap = new HashMap<>();
        ExecutionPlan lastNode = null;
        int lastDepth = -1;

        for (String[] row : planRows) {
            if (row.length == 0 || row[0] == null || row[0].startsWith("Total runtime")) {
                continue;
            }

            String planText = row[0].trim();
            if (planText.isEmpty()) continue;

            int depth = 0;
            while (depth < planText.length() && 
                   (planText.charAt(depth) == ' ' || planText.charAt(depth) == '→' || 
                    planText.charAt(depth) == '├' || planText.charAt(depth) == '│' ||
                    planText.charAt(depth) == '└' || planText.charAt(depth) == ' ')) {
                depth++;
            }
            depth = depth / 2;

            String nodeText = planText.replaceAll("^[→├│└\\s]+", "");
            
            ExecutionPlan node = new ExecutionPlan();
            
            Matcher costMatcher = COST_PATTERN.matcher(nodeText);
            if (costMatcher.find()) {
                try {
                    String[] costs = costMatcher.group(1).split("\\.\\.");
                    if (costs.length == 2) {
                        node.setCost(Double.parseDouble(costs[1]));
                    } else {
                        node.setCost(Double.parseDouble(costMatcher.group(1)));
                    }
                } catch (NumberFormatException ignored) {
                }
            }

            Matcher rowsMatcher = ROWS_PATTERN.matcher(nodeText);
            if (rowsMatcher.find()) {
                try {
                    node.setRows(Long.parseLong(rowsMatcher.group(1)));
                } catch (NumberFormatException ignored) {
                }
            }

            Matcher widthMatcher = WIDTH_PATTERN.matcher(nodeText);
            if (widthMatcher.find()) {
                try {
                    node.setBytes(Long.parseLong(widthMatcher.group(1)) * node.getRows());
                } catch (NumberFormatException ignored) {
                }
            }

            String operation = nodeText.replaceAll("\\s*\\(cost=.*\\)$", "");
            node.setOperation(operation);

            if (operation.contains(" on ")) {
                String[] parts = operation.split(" on ");
                node.setOperation(parts[0].trim());
                node.setObjectName(parts[1].trim().split("\\s+")[0]);
            } else if (operation.contains(" ON ")) {
                String[] parts = operation.split(" ON ");
                node.setOperation(parts[0].trim());
                node.setObjectName(parts[1].trim().split("\\s+")[0]);
            }

            node.setDepth(depth);
            node.setId(String.valueOf(nodeMap.size() + 1));

            if (depth == 0) {
                root = node;
            } else {
                ExecutionPlan parent = findParentByDepth(nodeMap, depth - 1);
                if (parent != null) {
                    parent.addChild(node);
                }
            }

            nodeMap.put(nodeMap.size(), node);
            lastNode = node;
            lastDepth = depth;
        }

        return root != null ? root : createEmptyPlan();
    }

    private ExecutionPlan findParentByDepth(Map<Integer, ExecutionPlan> nodeMap, int targetDepth) {
        List<ExecutionPlan> nodes = new ArrayList<>(nodeMap.values());
        for (int i = nodes.size() - 1; i >= 0; i--) {
            ExecutionPlan node = nodes.get(i);
            if (node.getDepth() == targetDepth) {
                return node;
            }
        }
        return nodes.isEmpty() ? null : nodes.get(0);
    }

    private ExecutionPlan parseOraclePlan(List<String[]> planRows) {
        if (planRows == null || planRows.isEmpty()) {
            return createEmptyPlan();
        }

        ExecutionPlan root = new ExecutionPlan();
        root.setId("0");
        root.setOperation("STATEMENT");
        root.setCost(0);

        Map<Integer, ExecutionPlan> nodeMap = new HashMap<>();
        nodeMap.put(0, root);

        for (String[] row : planRows) {
            if (row.length < 6) continue;

            try {
                int id = Integer.parseInt(row[0].trim());
                int parentId = row[1] != null && !row[1].trim().isEmpty() 
                        ? Integer.parseInt(row[1].trim()) : 0;

                ExecutionPlan node = new ExecutionPlan();
                node.setId(String.valueOf(id));
                node.setOperation(row[2] != null ? row[2].trim() : "");
                node.setObjectName(row[3] != null ? row[3].trim() : "");
                node.setObjectType(row[4] != null ? row[4].trim() : "");
                
                if (row[5] != null && !row[5].trim().isEmpty()) {
                    node.setRows(Long.parseLong(row[5].trim()));
                }
                if (row.length > 6 && row[6] != null && !row[6].trim().isEmpty()) {
                    node.setBytes(Long.parseLong(row[6].trim()));
                }
                if (row.length > 7 && row[7] != null && !row[7].trim().isEmpty()) {
                    node.setCost(Double.parseDouble(row[7].trim()));
                }

                nodeMap.put(id, node);

                ExecutionPlan parent = nodeMap.get(parentId);
                if (parent != null) {
                    parent.addChild(node);
                }
            } catch (NumberFormatException ignored) {
            }
        }

        return root;
    }

    private ExecutionPlan parseSQLServerPlan(List<String[]> planRows) {
        if (planRows == null || planRows.isEmpty()) {
            return createEmptyPlan();
        }

        ExecutionPlan root = new ExecutionPlan();
        root.setId("0");
        root.setOperation("QUERY PLAN");

        StringBuilder xmlBuilder = new StringBuilder();
        for (String[] row : planRows) {
            for (String cell : row) {
                if (cell != null) {
                    xmlBuilder.append(cell);
                }
            }
        }

        String xml = xmlBuilder.toString();
        Pattern stmtPattern = Pattern.compile("<Statement[^>]*EstimateRows=\"([\\d.]+)\"[^>]*EstimatedTotalSubtreeCost=\"([\\d.]+)\"");
        Matcher stmtMatcher = stmtPattern.matcher(xml);
        if (stmtMatcher.find()) {
            try {
                root.setRows((long) Double.parseDouble(stmtMatcher.group(1)));
                root.setCost(Double.parseDouble(stmtMatcher.group(2)));
            } catch (NumberFormatException ignored) {
            }
        }

        Pattern relOpPattern = Pattern.compile("<RelOp[^>]*PhysicalOp=\"([^\"]+)\"[^>]*LogicalOp=\"([^\"]+)\"[^>]*EstimateRows=\"([\\d.]+)\"[^>]*EstimatedTotalSubtreeCost=\"([\\d.]+)\"");
        Matcher relOpMatcher = relOpPattern.matcher(xml);
        
        while (relOpMatcher.find()) {
            ExecutionPlan node = new ExecutionPlan();
            node.setId(String.valueOf(root.getChildren().size() + 1));
            node.setOperation(relOpMatcher.group(1) + " (" + relOpMatcher.group(2) + ")");
            try {
                node.setRows((long) Double.parseDouble(relOpMatcher.group(3)));
                node.setCost(Double.parseDouble(relOpMatcher.group(4)));
            } catch (NumberFormatException ignored) {
            }
            
            int start = relOpMatcher.start();
            String remaining = xml.substring(start, Math.min(start + 500, xml.length()));
            Pattern tablePattern = Pattern.compile("Table=\"\\[([^\\]]+)\\]\"");
            Matcher tableMatcher = tablePattern.matcher(remaining);
            if (tableMatcher.find()) {
                node.setObjectName(tableMatcher.group(1));
            }

            root.addChild(node);
        }

        return root;
    }

    private ExecutionPlan parseGenericPlan(List<String[]> planRows) {
        ExecutionPlan root = new ExecutionPlan();
        root.setId("0");
        root.setOperation("QUERY PLAN");

        int rowNum = 0;
        for (String[] row : planRows) {
            if (row.length == 0) continue;

            ExecutionPlan node = new ExecutionPlan();
            node.setId(String.valueOf(++rowNum));
            node.setOperation(row[0] != null ? row[0] : "");
            
            StringBuilder detail = new StringBuilder();
            for (int i = 1; i < row.length; i++) {
                if (row[i] != null && !row[i].isEmpty()) {
                    if (detail.length() > 0) detail.append(", ");
                    detail.append(row[i]);
                }
            }
            node.setExtra(detail.toString());

            try {
                Pattern numPattern = Pattern.compile("(\\d+)");
                Matcher matcher = numPattern.matcher(detail.toString());
                if (matcher.find()) {
                    node.setRows(Long.parseLong(matcher.group(1)));
                }
            } catch (NumberFormatException ignored) {
            }

            root.addChild(node);
        }

        return root;
    }

    private ExecutionPlan createEmptyPlan() {
        ExecutionPlan root = new ExecutionPlan();
        root.setId("0");
        root.setOperation("NO PLAN AVAILABLE");
        return root;
    }

    public String formatPlanAsText(ExecutionPlan plan) {
        StringBuilder sb = new StringBuilder();
        sb.append("Execution Plan\n");
        sb.append("=".repeat(80)).append("\n\n");
        sb.append(plan.toTreeString());
        sb.append("\n");
        sb.append("=".repeat(80)).append("\n");
        sb.append("Total Cost: ").append(String.format("%.2f", plan.getTotalCost())).append("\n");
        sb.append("Total Rows: ").append(plan.getTotalRows()).append("\n");
        return sb.toString();
    }

    public List<Map<String, String>> getPlanStats(ExecutionPlan plan) {
        List<Map<String, String>> stats = new ArrayList<>();
        for (ExecutionPlan node : plan.getAllNodes()) {
            Map<String, String> stat = new LinkedHashMap<>();
            stat.put("operation", node.getOperation());
            stat.put("object", node.getObjectName() != null ? node.getObjectName() : "-");
            stat.put("rows", String.valueOf(node.getRows()));
            stat.put("cost", String.format("%.2f", node.getCost()));
            stat.put("type", node.getOperationType());
            stat.put("isFullScan", String.valueOf(node.isFullTableScan()));
            stats.add(stat);
        }
        return stats;
    }
}
