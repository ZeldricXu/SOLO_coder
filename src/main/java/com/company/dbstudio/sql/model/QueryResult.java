package com.company.dbstudio.sql.model;

import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class QueryResult {
    private final SimpleStringProperty sql;
    private final SimpleStringProperty connectionId;
    private final SimpleObjectProperty<ObservableList<ObservableList<Object>>> data;
    private final SimpleObjectProperty<List<ColumnInfo>> columns;
    private final SimpleObjectProperty<Long> executionTime;
    private final SimpleObjectProperty<Integer> rowCount;
    private final SimpleObjectProperty<Long> affectedRows;
    private final SimpleStringProperty errorMessage;
    private final SimpleObjectProperty<Boolean> hasError;
    private final SimpleObjectProperty<ExecutionPlan> executionPlan;
    private final SimpleStringProperty queryType;

    public QueryResult() {
        this.sql = new SimpleStringProperty();
        this.connectionId = new SimpleStringProperty();
        this.data = new SimpleObjectProperty<>(FXCollections.observableArrayList());
        this.columns = new SimpleObjectProperty<>(new ArrayList<>());
        this.executionTime = new SimpleObjectProperty<>(0L);
        this.rowCount = new SimpleObjectProperty<>(0);
        this.affectedRows = new SimpleObjectProperty<>(0L);
        this.errorMessage = new SimpleStringProperty();
        this.hasError = new SimpleObjectProperty<>(false);
        this.executionPlan = new SimpleObjectProperty<>();
        this.queryType = new SimpleStringProperty();
    }

    public QueryResult(String sql, String connectionId) {
        this();
        this.sql.set(sql);
        this.connectionId.set(connectionId);
    }

    public String getSql() {
        return sql.get();
    }

    public SimpleStringProperty sqlProperty() {
        return sql;
    }

    public void setSql(String sql) {
        this.sql.set(sql);
    }

    public String getConnectionId() {
        return connectionId.get();
    }

    public SimpleStringProperty connectionIdProperty() {
        return connectionId;
    }

    public void setConnectionId(String connectionId) {
        this.connectionId.set(connectionId);
    }

    public ObservableList<ObservableList<Object>> getData() {
        return data.get();
    }

    public SimpleObjectProperty<ObservableList<ObservableList<Object>>> dataProperty() {
        return data;
    }

    public void setData(ObservableList<ObservableList<Object>> data) {
        this.data.set(data);
        this.rowCount.set(data != null ? data.size() : 0);
    }

    public void addRow(ObservableList<Object> row) {
        this.data.get().add(row);
        this.rowCount.set(this.rowCount.get() + 1);
    }

    public List<ColumnInfo> getColumns() {
        return columns.get();
    }

    public SimpleObjectProperty<List<ColumnInfo>> columnsProperty() {
        return columns;
    }

    public void setColumns(List<ColumnInfo> columns) {
        this.columns.set(columns);
    }

    public void setColumnsFromMetaData(ResultSetMetaData metaData) throws SQLException {
        List<ColumnInfo> columnInfos = new ArrayList<>();
        int columnCount = metaData.getColumnCount();
        for (int i = 1; i <= columnCount; i++) {
            ColumnInfo col = new ColumnInfo(
                    metaData.getColumnName(i),
                    metaData.getColumnLabel(i),
                    metaData.getColumnTypeName(i),
                    metaData.getColumnType(i),
                    metaData.getPrecision(i),
                    metaData.getScale(i),
                    metaData.isNullable(i) == ResultSetMetaData.columnNullable
            );
            columnInfos.add(col);
        }
        this.columns.set(columnInfos);
    }

    public Long getExecutionTime() {
        return executionTime.get();
    }

    public SimpleObjectProperty<Long> executionTimeProperty() {
        return executionTime;
    }

    public void setExecutionTime(Long executionTime) {
        this.executionTime.set(executionTime);
    }

    public Integer getRowCount() {
        return rowCount.get();
    }

    public SimpleObjectProperty<Integer> rowCountProperty() {
        return rowCount;
    }

    public void setRowCount(Integer rowCount) {
        this.rowCount.set(rowCount);
    }

    public Long getAffectedRows() {
        return affectedRows.get();
    }

    public SimpleObjectProperty<Long> affectedRowsProperty() {
        return affectedRows;
    }

    public void setAffectedRows(Long affectedRows) {
        this.affectedRows.set(affectedRows);
    }

    public String getErrorMessage() {
        return errorMessage.get();
    }

    public SimpleStringProperty errorMessageProperty() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage.set(errorMessage);
        this.hasError.set(true);
    }

    public Boolean getHasError() {
        return hasError.get();
    }

    public SimpleObjectProperty<Boolean> hasErrorProperty() {
        return hasError;
    }

    public void setHasError(Boolean hasError) {
        this.hasError.set(hasError);
    }

    public ExecutionPlan getExecutionPlan() {
        return executionPlan.get();
    }

    public SimpleObjectProperty<ExecutionPlan> executionPlanProperty() {
        return executionPlan;
    }

    public void setExecutionPlan(ExecutionPlan executionPlan) {
        this.executionPlan.set(executionPlan);
    }

    public String getQueryType() {
        return queryType.get();
    }

    public SimpleStringProperty queryTypeProperty() {
        return queryType;
    }

    public void setQueryType(String queryType) {
        this.queryType.set(queryType);
    }

    public boolean isResultSet() {
        return data.get() != null && !data.get().isEmpty();
    }

    public boolean isUpdateResult() {
        return affectedRows.get() > 0;
    }

    public void clear() {
        if (data.get() != null) {
            data.get().clear();
        }
        columns.get().clear();
        rowCount.set(0);
        affectedRows.set(0L);
        errorMessage.set(null);
        hasError.set(false);
        executionPlan.set(null);
    }

    public static class ColumnInfo {
        private final String name;
        private final String label;
        private final String typeName;
        private final int type;
        private final int precision;
        private final int scale;
        private final boolean nullable;

        public ColumnInfo(String name, String label, String typeName, int type, 
                         int precision, int scale, boolean nullable) {
            this.name = name;
            this.label = label;
            this.typeName = typeName;
            this.type = type;
            this.precision = precision;
            this.scale = scale;
            this.nullable = nullable;
        }

        public String getName() {
            return name;
        }

        public String getLabel() {
            return label != null && !label.isEmpty() ? label : name;
        }

        public String getTypeName() {
            return typeName;
        }

        public int getType() {
            return type;
        }

        public int getPrecision() {
            return precision;
        }

        public int getScale() {
            return scale;
        }

        public boolean isNullable() {
            return nullable;
        }

        public String getFullTypeName() {
            if (precision > 0 && scale > 0) {
                return typeName + "(" + precision + "," + scale + ")";
            } else if (precision > 0) {
                return typeName + "(" + precision + ")";
            }
            return typeName;
        }
    }
}
