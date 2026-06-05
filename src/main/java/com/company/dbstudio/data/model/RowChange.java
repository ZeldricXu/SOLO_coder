package com.company.dbstudio.data.model;

import java.util.LinkedHashMap;
import java.util.Map;

public class RowChange {
    public enum ChangeType {
        INSERT, UPDATE, DELETE
    }

    private final ChangeType type;
    private final String tableName;
    private final Map<String, Object> oldValues;
    private final Map<String, Object> newValues;
    private final Map<String, Integer> columnTypes;
    private final Map<String, Object> primaryKeys;

    public RowChange(ChangeType type, String tableName) {
        this.type = type;
        this.tableName = tableName;
        this.oldValues = new LinkedHashMap<>();
        this.newValues = new LinkedHashMap<>();
        this.columnTypes = new LinkedHashMap<>();
        this.primaryKeys = new LinkedHashMap<>();
    }

    public static RowChange forInsert(String tableName) {
        return new RowChange(ChangeType.INSERT, tableName);
    }

    public static RowChange forUpdate(String tableName) {
        return new RowChange(ChangeType.UPDATE, tableName);
    }

    public static RowChange forDelete(String tableName) {
        return new RowChange(ChangeType.DELETE, tableName);
    }

    public ChangeType getType() {
        return type;
    }

    public String getTableName() {
        return tableName;
    }

    public Map<String, Object> getOldValues() {
        return oldValues;
    }

    public Map<String, Object> getNewValues() {
        return newValues;
    }

    public Map<String, Integer> getColumnTypes() {
        return columnTypes;
    }

    public Map<String, Object> getPrimaryKeys() {
        return primaryKeys;
    }

    public void addOldValue(String column, Object value, int sqlType) {
        oldValues.put(column, value);
        columnTypes.put(column, sqlType);
    }

    public void addNewValue(String column, Object value, int sqlType) {
        newValues.put(column, value);
        columnTypes.put(column, sqlType);
    }

    public void addPrimaryKey(String column, Object value, int sqlType) {
        primaryKeys.put(column, value);
        columnTypes.put(column, sqlType);
    }

    public boolean hasChanges() {
        if (type == ChangeType.INSERT) {
            return !newValues.isEmpty();
        } else if (type == ChangeType.DELETE) {
            return !primaryKeys.isEmpty();
        } else {
            for (Map.Entry<String, Object> entry : newValues.entrySet()) {
                Object oldVal = oldValues.get(entry.getKey());
                Object newVal = entry.getValue();
                if (!valuesEqual(oldVal, newVal)) {
                    return true;
                }
            }
            return false;
        }
    }

    private boolean valuesEqual(Object a, Object b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        if (a instanceof Number && b instanceof Number) {
            return ((Number) a).doubleValue() == ((Number) b).doubleValue();
        }
        return a.equals(b);
    }

    public String generateSql() {
        return switch (type) {
            case INSERT -> generateInsertSql();
            case UPDATE -> generateUpdateSql();
            case DELETE -> generateDeleteSql();
        };
    }

    private String generateInsertSql() {
        StringBuilder sql = new StringBuilder("INSERT INTO ");
        sql.append(tableName);
        
        StringBuilder columns = new StringBuilder("(");
        StringBuilder values = new StringBuilder("VALUES (");
        
        boolean first = true;
        for (Map.Entry<String, Object> entry : newValues.entrySet()) {
            if (!first) {
                columns.append(", ");
                values.append(", ");
            }
            columns.append(entry.getKey());
            values.append(formatValue(entry.getValue(), columnTypes.get(entry.getKey())));
            first = false;
        }
        
        columns.append(")");
        values.append(")");
        
        sql.append(" ").append(columns).append(" ").append(values).append(";");
        return sql.toString();
    }

    private String generateUpdateSql() {
        StringBuilder sql = new StringBuilder("UPDATE ");
        sql.append(tableName).append(" SET ");
        
        boolean first = true;
        for (Map.Entry<String, Object> entry : newValues.entrySet()) {
            if (!first) {
                sql.append(", ");
            }
            sql.append(entry.getKey()).append(" = ");
            sql.append(formatValue(entry.getValue(), columnTypes.get(entry.getKey())));
            first = false;
        }
        
        if (!primaryKeys.isEmpty()) {
            sql.append(" WHERE ");
            first = true;
            for (Map.Entry<String, Object> entry : primaryKeys.entrySet()) {
                if (!first) {
                    sql.append(" AND ");
                }
                sql.append(entry.getKey()).append(" = ");
                sql.append(formatValue(entry.getValue(), columnTypes.get(entry.getKey())));
                first = false;
            }
        } else {
            sql.append(" WHERE ");
            first = true;
            for (Map.Entry<String, Object> entry : oldValues.entrySet()) {
                if (!first) {
                    sql.append(" AND ");
                }
                sql.append(entry.getKey()).append(" = ");
                sql.append(formatValue(entry.getValue(), columnTypes.get(entry.getKey())));
                first = false;
            }
        }
        
        sql.append(";");
        return sql.toString();
    }

    private String generateDeleteSql() {
        StringBuilder sql = new StringBuilder("DELETE FROM ");
        sql.append(tableName);
        
        if (!primaryKeys.isEmpty()) {
            sql.append(" WHERE ");
            boolean first = true;
            for (Map.Entry<String, Object> entry : primaryKeys.entrySet()) {
                if (!first) {
                    sql.append(" AND ");
                }
                sql.append(entry.getKey()).append(" = ");
                sql.append(formatValue(entry.getValue(), columnTypes.get(entry.getKey())));
                first = false;
            }
        } else {
            sql.append(" WHERE ");
            boolean first = true;
            for (Map.Entry<String, Object> entry : oldValues.entrySet()) {
                if (!first) {
                    sql.append(" AND ");
                }
                sql.append(entry.getKey()).append(" = ");
                sql.append(formatValue(entry.getValue(), columnTypes.get(entry.getKey())));
                first = false;
            }
        }
        
        sql.append(";");
        return sql.toString();
    }

    private String formatValue(Object value, Integer sqlType) {
        if (value == null) {
            return "NULL";
        }
        
        if (value instanceof Number) {
            return value.toString();
        }
        
        if (value instanceof Boolean) {
            return (Boolean) value ? "1" : "0";
        }
        
        String strValue = value.toString();
        strValue = strValue.replace("'", "''");
        return "'" + strValue + "'";
    }

    @Override
    public String toString() {
        return type + " on " + tableName + ": " + generateSql();
    }
}
