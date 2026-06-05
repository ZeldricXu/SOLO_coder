package com.company.dbstudio.sql.model;

import java.util.List;

public class IndexSuggestion {
    private String tableName;
    private List<String> columns;
    private String indexType;
    private String reason;
    private double estimatedImprovement;
    private String ddl;
    private String explanation;

    public IndexSuggestion() {
    }

    public IndexSuggestion(String tableName, List<String> columns, String indexType, String reason) {
        this.tableName = tableName;
        this.columns = columns;
        this.indexType = indexType;
        this.reason = reason;
        this.ddl = generateDDL();
        this.explanation = generateExplanation();
    }

    public String getTableName() {
        return tableName;
    }

    public void setTableName(String tableName) {
        this.tableName = tableName;
    }

    public List<String> getColumns() {
        return columns;
    }

    public void setColumns(List<String> columns) {
        this.columns = columns;
        this.ddl = generateDDL();
    }

    public String getIndexType() {
        return indexType;
    }

    public void setIndexType(String indexType) {
        this.indexType = indexType;
        this.ddl = generateDDL();
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public double getEstimatedImprovement() {
        return estimatedImprovement;
    }

    public void setEstimatedImprovement(double estimatedImprovement) {
        this.estimatedImprovement = estimatedImprovement;
    }

    public String getDdl() {
        return ddl;
    }

    public void setDdl(String ddl) {
        this.ddl = ddl;
    }

    public String getExplanation() {
        return explanation;
    }

    public void setExplanation(String explanation) {
        this.explanation = explanation;
    }

    private String generateDDL() {
        if (tableName == null || columns == null || columns.isEmpty()) {
            return "";
        }
        String indexName = "idx_" + tableName.toLowerCase() + "_" + 
                          String.join("_", columns).toLowerCase();
        String columnsStr = String.join(", ", columns);
        
        return switch (indexType != null ? indexType.toUpperCase() : "INDEX") {
            case "UNIQUE" -> String.format("CREATE UNIQUE INDEX %s ON %s (%s);", 
                                          indexName, tableName, columnsStr);
            case "FULLTEXT" -> String.format("CREATE FULLTEXT INDEX %s ON %s (%s);", 
                                           indexName, tableName, columnsStr);
            case "PRIMARY" -> String.format("ALTER TABLE %s ADD PRIMARY KEY (%s);", 
                                           tableName, columnsStr);
            default -> String.format("CREATE INDEX %s ON %s (%s);", 
                                    indexName, tableName, columnsStr);
        };
    }

    private String generateExplanation() {
        StringBuilder sb = new StringBuilder();
        sb.append("建议在表 ").append(tableName).append(" 上创建");
        if ("UNIQUE".equalsIgnoreCase(indexType)) {
            sb.append("唯一");
        } else if ("FULLTEXT".equalsIgnoreCase(indexType)) {
            sb.append("全文");
        }
        sb.append("索引 (");
        sb.append(String.join(", ", columns));
        sb.append(")");
        if (reason != null) {
            sb.append("，原因：").append(reason);
        }
        if (estimatedImprovement > 0) {
            sb.append(String.format("，预计性能提升约 %.1f%%", estimatedImprovement * 100));
        }
        return sb.toString();
    }

    public String getColumnString() {
        return columns != null ? String.join(", ", columns) : "";
    }

    @Override
    public String toString() {
        return getExplanation();
    }
}
