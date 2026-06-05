package com.company.dbstudio.sql.model;

import java.util.ArrayList;
import java.util.List;

public class ExecutionPlan {
    private String id;
    private String operation;
    private String objectName;
    private String objectType;
    private long rows;
    private long bytes;
    private double cost;
    private int depth;
    private int position;
    private String predicate;
    private String filter;
    private String access;
    private List<ExecutionPlan> children;
    private ExecutionPlan parent;

    public ExecutionPlan() {
        this.children = new ArrayList<>();
    }

    public ExecutionPlan(String id, String operation, String objectName, long rows, double cost) {
        this();
        this.id = id;
        this.operation = operation;
        this.objectName = objectName;
        this.rows = rows;
        this.cost = cost;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getOperation() {
        return operation;
    }

    public void setOperation(String operation) {
        this.operation = operation;
    }

    public String getObjectName() {
        return objectName;
    }

    public void setObjectName(String objectName) {
        this.objectName = objectName;
    }

    public String getObjectType() {
        return objectType;
    }

    public void setObjectType(String objectType) {
        this.objectType = objectType;
    }

    public long getRows() {
        return rows;
    }

    public void setRows(long rows) {
        this.rows = rows;
    }

    public long getBytes() {
        return bytes;
    }

    public void setBytes(long bytes) {
        this.bytes = bytes;
    }

    public double getCost() {
        return cost;
    }

    public void setCost(double cost) {
        this.cost = cost;
    }

    public int getDepth() {
        return depth;
    }

    public void setDepth(int depth) {
        this.depth = depth;
    }

    public int getPosition() {
        return position;
    }

    public void setPosition(int position) {
        this.position = position;
    }

    public String getPredicate() {
        return predicate;
    }

    public void setPredicate(String predicate) {
        this.predicate = predicate;
    }

    public String getFilter() {
        return filter;
    }

    public void setFilter(String filter) {
        this.filter = filter;
    }

    public String getAccess() {
        return access;
    }

    public void setAccess(String access) {
        this.access = access;
    }

    public List<ExecutionPlan> getChildren() {
        return children;
    }

    public void setChildren(List<ExecutionPlan> children) {
        this.children = children;
    }

    public ExecutionPlan getParent() {
        return parent;
    }

    public void setParent(ExecutionPlan parent) {
        this.parent = parent;
    }

    public void addChild(ExecutionPlan child) {
        child.setParent(this);
        child.setDepth(this.depth + 1);
        this.children.add(child);
    }

    public boolean isFullTableScan() {
        return operation != null && operation.toUpperCase().contains("FULL") 
                && operation.toUpperCase().contains("SCAN");
    }

    public boolean isIndexScan() {
        return operation != null && operation.toUpperCase().contains("INDEX") 
                && operation.toUpperCase().contains("SCAN");
    }

    public boolean isIndexSeek() {
        return operation != null && operation.toUpperCase().contains("INDEX") 
                && operation.toUpperCase().contains("SEEK");
    }

    public boolean isNestedLoop() {
        return operation != null && operation.toUpperCase().contains("NESTED LOOPS");
    }

    public boolean isHashJoin() {
        return operation != null && operation.toUpperCase().contains("HASH JOIN");
    }

    public boolean isSortOperation() {
        return operation != null && operation.toUpperCase().contains("SORT");
    }

    public List<ExecutionPlan> getAllNodes() {
        List<ExecutionPlan> allNodes = new ArrayList<>();
        collectNodes(this, allNodes);
        return allNodes;
    }

    private void collectNodes(ExecutionPlan node, List<ExecutionPlan> collector) {
        collector.add(node);
        for (ExecutionPlan child : node.getChildren()) {
            collectNodes(child, collector);
        }
    }

    public double getTotalCost() {
        return getAllNodes().stream()
                .mapToDouble(ExecutionPlan::getCost)
                .sum();
    }

    public long getTotalRows() {
        return getAllNodes().stream()
                .mapToLong(ExecutionPlan::getRows)
                .sum();
    }

    public String getOperationType() {
        if (operation == null) {
            return "OTHER";
        }
        String upperOp = operation.toUpperCase();
        if (upperOp.contains("SELECT")) return "SELECT";
        if (upperOp.contains("INSERT")) return "INSERT";
        if (upperOp.contains("UPDATE")) return "UPDATE";
        if (upperOp.contains("DELETE")) return "DELETE";
        if (upperOp.contains("JOIN")) return "JOIN";
        if (upperOp.contains("SORT")) return "SORT";
        if (upperOp.contains("GROUP")) return "GROUP";
        if (upperOp.contains("AGGREGATE")) return "AGGREGATE";
        return "OTHER";
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("|").append(" ".repeat(depth * 2));
        sb.append(operation);
        if (objectName != null) {
            sb.append(" ON ").append(objectName);
        }
        sb.append(" (Cost: ").append(cost).append(", Rows: ").append(rows).append(")");
        return sb.toString();
    }

    public String toTreeString() {
        StringBuilder sb = new StringBuilder();
        buildTreeString(sb, "", true);
        return sb.toString();
    }

    private void buildTreeString(StringBuilder sb, String prefix, boolean isLast) {
        sb.append(prefix);
        sb.append(isLast ? "└── " : "├── ");
        sb.append(operation);
        if (objectName != null) {
            sb.append(" [").append(objectName).append("]");
        }
        sb.append(" (rows=").append(rows).append(", cost=").append(cost).append(")");
        sb.append("\n");

        String childPrefix = prefix + (isLast ? "    " : "│   ");
        for (int i = 0; i < children.size(); i++) {
            children.get(i).buildTreeString(sb, childPrefix, i == children.size() - 1);
        }
    }
}
