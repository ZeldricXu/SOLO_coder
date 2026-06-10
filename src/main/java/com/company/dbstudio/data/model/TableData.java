package com.company.dbstudio.data.model;

import com.company.dbstudio.sql.model.QueryResult;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.ObservableList;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

public class TableData {
    private final SimpleStringProperty tableName;
    private final SimpleStringProperty schemaName;
    private final SimpleObjectProperty<ObservableList<ObservableList<Object>>> rows;
    private final SimpleObjectProperty<List<ColumnMetadata>> columns;
    private final SimpleObjectProperty<Long> totalRows;
    private final SimpleObjectProperty<Integer> currentPage;
    private final SimpleObjectProperty<Integer> pageSize;
    private final SimpleObjectProperty<Integer> totalPages;
    private final SimpleStringProperty whereClause;
    private final SimpleStringProperty orderByClause;
    private final SimpleObjectProperty<Long> generation;
    private final SimpleObjectProperty<Long> loadedAt;
    private static final AtomicLong generationCounter = new AtomicLong(0);

    public TableData() {
        this.tableName = new SimpleStringProperty();
        this.schemaName = new SimpleStringProperty();
        this.rows = new SimpleObjectProperty<>();
        this.columns = new SimpleObjectProperty<>(new ArrayList<>());
        this.totalRows = new SimpleObjectProperty<>(0L);
        this.currentPage = new SimpleObjectProperty<>(1);
        this.pageSize = new SimpleObjectProperty<>(100);
        this.totalPages = new SimpleObjectProperty<>(1);
        this.whereClause = new SimpleStringProperty();
        this.orderByClause = new SimpleStringProperty();
        this.generation = new SimpleObjectProperty<>(0L);
        this.loadedAt = new SimpleObjectProperty<>(0L);
    }

    public TableData(String tableName, String schemaName) {
        this();
        this.tableName.set(tableName);
        this.schemaName.set(schemaName);
    }

    public String getTableName() {
        return tableName.get();
    }

    public SimpleStringProperty tableNameProperty() {
        return tableName;
    }

    public void setTableName(String tableName) {
        this.tableName.set(tableName);
    }

    public String getSchemaName() {
        return schemaName.get();
    }

    public SimpleStringProperty schemaNameProperty() {
        return schemaName;
    }

    public void setSchemaName(String schemaName) {
        this.schemaName.set(schemaName);
    }

    public ObservableList<ObservableList<Object>> getRows() {
        return rows.get();
    }

    public SimpleObjectProperty<ObservableList<ObservableList<Object>>> rowsProperty() {
        return rows;
    }

    public void setRows(ObservableList<ObservableList<Object>> rows) {
        this.rows.set(rows);
    }

    public List<ColumnMetadata> getColumns() {
        return columns.get();
    }

    public SimpleObjectProperty<List<ColumnMetadata>> columnsProperty() {
        return columns;
    }

    public void setColumns(List<ColumnMetadata> columns) {
        this.columns.set(columns);
    }

    public Long getTotalRows() {
        return totalRows.get();
    }

    public SimpleObjectProperty<Long> totalRowsProperty() {
        return totalRows;
    }

    public void setTotalRows(Long totalRows) {
        this.totalRows.set(totalRows);
        updateTotalPages();
    }

    public Integer getCurrentPage() {
        return currentPage.get();
    }

    public SimpleObjectProperty<Integer> currentPageProperty() {
        return currentPage;
    }

    public void setCurrentPage(Integer currentPage) {
        this.currentPage.set(currentPage);
    }

    public Integer getPageSize() {
        return pageSize.get();
    }

    public SimpleObjectProperty<Integer> pageSizeProperty() {
        return pageSize;
    }

    public void setPageSize(Integer pageSize) {
        this.pageSize.set(pageSize);
        updateTotalPages();
    }

    public Integer getTotalPages() {
        return totalPages.get();
    }

    public SimpleObjectProperty<Integer> totalPagesProperty() {
        return totalPages;
    }

    private void updateTotalPages() {
        if (totalRows.get() != null && pageSize.get() != null && pageSize.get() > 0) {
            int pages = (int) Math.ceil((double) totalRows.get() / pageSize.get());
            this.totalPages.set(Math.max(1, pages));
        }
    }

    public String getWhereClause() {
        return whereClause.get();
    }

    public SimpleStringProperty whereClauseProperty() {
        return whereClause;
    }

    public void setWhereClause(String whereClause) {
        this.whereClause.set(whereClause);
    }

    public String getOrderByClause() {
        return orderByClause.get();
    }

    public SimpleStringProperty orderByClauseProperty() {
        return orderByClause;
    }

    public void setOrderByClause(String orderByClause) {
        this.orderByClause.set(orderByClause);
    }

    public Long getGeneration() {
        return generation.get();
    }

    public SimpleObjectProperty<Long> generationProperty() {
        return generation;
    }

    public void setGeneration(Long generation) {
        this.generation.set(generation);
    }

    public long incrementGeneration() {
        long newGeneration = generationCounter.incrementAndGet();
        this.generation.set(newGeneration);
        this.loadedAt.set(System.currentTimeMillis());
        return newGeneration;
    }

    public Long getLoadedAt() {
        return loadedAt.get();
    }

    public SimpleObjectProperty<Long> loadedAtProperty() {
        return loadedAt;
    }

    public void setLoadedAt(Long loadedAt) {
        this.loadedAt.set(loadedAt);
    }

    public boolean hasNextPage() {
        return currentPage.get() < totalPages.get();
    }

    public boolean hasPreviousPage() {
        return currentPage.get() > 1;
    }

    public void nextPage() {
        if (hasNextPage()) {
            currentPage.set(currentPage.get() + 1);
        }
    }

    public void previousPage() {
        if (hasPreviousPage()) {
            currentPage.set(currentPage.get() - 1);
        }
    }

    public void firstPage() {
        currentPage.set(1);
    }

    public void lastPage() {
        currentPage.set(totalPages.get());
    }

    public String getFullTableName() {
        if (schemaName.get() != null && !schemaName.get().isEmpty()) {
            return schemaName.get() + "." + tableName.get();
        }
        return tableName.get();
    }

    public static class ColumnMetadata {
        private final String name;
        private final String type;
        private final int sqlType;
        private final int precision;
        private final int scale;
        private final boolean nullable;
        private final boolean primaryKey;
        private final boolean autoIncrement;
        private final String defaultValue;
        private final String comment;
        private final boolean editable;

        public ColumnMetadata(String name, String type, int sqlType) {
            this(name, type, sqlType, 0, 0, true, false, false, null, null, true);
        }

        public ColumnMetadata(String name, String type, int sqlType, int precision, int scale,
                             boolean nullable, boolean primaryKey, boolean autoIncrement,
                             String defaultValue, String comment, boolean editable) {
            this.name = name;
            this.type = type;
            this.sqlType = sqlType;
            this.precision = precision;
            this.scale = scale;
            this.nullable = nullable;
            this.primaryKey = primaryKey;
            this.autoIncrement = autoIncrement;
            this.defaultValue = defaultValue;
            this.comment = comment;
            this.editable = editable;
        }

        public String getName() {
            return name;
        }

        public String getType() {
            return type;
        }

        public int getSqlType() {
            return sqlType;
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

        public boolean isPrimaryKey() {
            return primaryKey;
        }

        public boolean isAutoIncrement() {
            return autoIncrement;
        }

        public String getDefaultValue() {
            return defaultValue;
        }

        public String getComment() {
            return comment;
        }

        public boolean isEditable() {
            return editable && !primaryKey && !autoIncrement;
        }

        public boolean isBlobType() {
            return type.toUpperCase().contains("BLOB") || type.toUpperCase().contains("BINARY");
        }

        public boolean isJsonType() {
            return type.toUpperCase().contains("JSON");
        }

        public boolean isClobType() {
            return type.toUpperCase().contains("CLOB") || type.toUpperCase().contains("TEXT");
        }

        public boolean isNumericType() {
            return type.toUpperCase().contains("INT") || type.toUpperCase().contains("DECIMAL") 
                    || type.toUpperCase().contains("FLOAT") || type.toUpperCase().contains("DOUBLE")
                    || type.toUpperCase().contains("NUMERIC");
        }

        public boolean isDateType() {
            return type.toUpperCase().contains("DATE") || type.toUpperCase().contains("TIME")
                    || type.toUpperCase().contains("TIMESTAMP");
        }

        public boolean isBooleanType() {
            return type.toUpperCase().contains("BOOL") || type.toUpperCase().contains("BIT");
        }

        public String getFullTypeName() {
            if (precision > 0 && scale > 0) {
                return type + "(" + precision + "," + scale + ")";
            } else if (precision > 0) {
                return type + "(" + precision + ")";
            }
            return type;
        }

        @Override
        public String toString() {
            return name + " (" + getFullTypeName() + ")";
        }
    }
}
