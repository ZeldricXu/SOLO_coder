package com.company.dbstudio.schema.service;

import com.company.dbstudio.connection.datasource.DataSourceRegistry;
import com.company.dbstudio.connection.model.ConnectionType;
import com.company.dbstudio.core.ApplicationContext;
import com.company.dbstudio.core.model.Result;
import com.company.dbstudio.schema.model.SchemaObject;
import com.company.dbstudio.schema.model.SchemaObject.ObjectType;
import javafx.application.Platform;

import java.sql.*;
import java.util.*;
import java.util.function.Consumer;

public class SchemaService {

    private final DataSourceRegistry dataSourceRegistry;

    public SchemaService() {
        this.dataSourceRegistry = ApplicationContext.getBean(DataSourceRegistry.class);
    }

    public Result<List<SchemaObject>> loadSchemas(String connectionId) {
        List<SchemaObject> schemas = new ArrayList<>();

        try (Connection conn = dataSourceRegistry.getConnection(connectionId)) {
            DatabaseMetaData metaData = conn.getMetaData();

            try (ResultSet rs = metaData.getSchemas()) {
                while (rs.next()) {
                    String schemaName = rs.getString("TABLE_SCHEM");
                    SchemaObject schema = new SchemaObject(ObjectType.SCHEMA, schemaName, schemaName);
                    schemas.add(schema);
                }
            }

            if (schemas.isEmpty()) {
                String catalog = conn.getCatalog();
                if (catalog != null && !catalog.isEmpty()) {
                    SchemaObject schema = new SchemaObject(ObjectType.SCHEMA, catalog, catalog);
                    schemas.add(schema);
                }
            }

            Collections.sort(schemas, Comparator.comparing(SchemaObject::getName));
            return Result.success(schemas);
        } catch (SQLException e) {
            return Result.failure("加载Schema失败: " + e.getMessage());
        }
    }

    public Result<List<SchemaObject>> loadTables(String connectionId, String schemaName) {
        List<SchemaObject> tables = new ArrayList<>();

        try (Connection conn = dataSourceRegistry.getConnection(connectionId)) {
            DatabaseMetaData metaData = conn.getMetaData();

            try (ResultSet rs = metaData.getTables(null, schemaName, "%",
                    new String[]{"TABLE", "VIEW"})) {
                while (rs.next()) {
                    String tableName = rs.getString("TABLE_NAME");
                    String tableType = rs.getString("TABLE_TYPE");
                    String comment = rs.getString("REMARKS");

                    ObjectType type = "VIEW".equalsIgnoreCase(tableType) ? ObjectType.VIEW : ObjectType.TABLE;
                    SchemaObject table = new SchemaObject(type, tableName, schemaName);
                    table.setComment(comment);
                    tables.add(table);
                }
            }

            Collections.sort(tables, Comparator.comparing(SchemaObject::getName));
            return Result.success(tables);
        } catch (SQLException e) {
            return Result.failure("加载表列表失败: " + e.getMessage());
        }
    }

    public Result<List<SchemaObject>> loadTableChildren(String connectionId, SchemaObject table) {
        List<SchemaObject> children = new ArrayList<>();

        try (Connection conn = dataSourceRegistry.getConnection(connectionId)) {
            DatabaseMetaData metaData = conn.getMetaData();
            String schemaName = table.getSchemaName();
            String tableName = table.getName();

            List<SchemaObject> columns = loadColumns(metaData, schemaName, tableName);
            List<SchemaObject> primaryKeys = loadPrimaryKeys(metaData, schemaName, tableName);
            List<SchemaObject> indexes = loadIndexes(metaData, schemaName, tableName);
            List<SchemaObject> foreignKeys = loadForeignKeys(metaData, schemaName, tableName);
            List<SchemaObject> triggers = loadTriggers(conn, schemaName, tableName);

            children.addAll(columns);
            children.addAll(primaryKeys);
            children.addAll(indexes);
            children.addAll(foreignKeys);
            children.addAll(triggers);

            table.setLoaded(true);
            return Result.success(children);
        } catch (SQLException e) {
            return Result.failure("加载表详情失败: " + e.getMessage());
        }
    }

    private List<SchemaObject> loadColumns(DatabaseMetaData metaData, String schemaName, String tableName)
            throws SQLException {
        List<SchemaObject> columns = new ArrayList<>();

        try (ResultSet rs = metaData.getColumns(null, schemaName, tableName, null)) {
            while (rs.next()) {
                String colName = rs.getString("COLUMN_NAME");
                String typeName = rs.getString("TYPE_NAME");
                int precision = rs.getInt("COLUMN_SIZE");
                int scale = rs.getInt("DECIMAL_DIGITS");
                boolean nullable = rs.getInt("NULLABLE") == DatabaseMetaData.columnNullable;
                String comment = rs.getString("REMARKS");
                String defaultValue = rs.getString("COLUMN_DEF");
                boolean autoIncrement = "YES".equalsIgnoreCase(rs.getString("IS_AUTOINCREMENT"));

                StringBuilder fullType = new StringBuilder(typeName);
                if (precision > 0) {
                    fullType.append("(").append(precision);
                    if (scale > 0) {
                        fullType.append(",").append(scale);
                    }
                    fullType.append(")");
                }

                SchemaObject col = new SchemaObject(ObjectType.COLUMN, colName, schemaName, tableName);
                col.setComment(String.format("%s %s%s%s",
                        fullType,
                        nullable ? "" : " NOT NULL",
                        autoIncrement ? " AUTO_INCREMENT" : "",
                        defaultValue != null ? " DEFAULT " + defaultValue : ""));
                columns.add(col);
            }
        }

        return columns;
    }

    private List<SchemaObject> loadPrimaryKeys(DatabaseMetaData metaData, String schemaName, String tableName)
            throws SQLException {
        List<SchemaObject> pks = new ArrayList<>();
        Map<String, List<String>> pkColumns = new LinkedHashMap<>();

        try (ResultSet rs = metaData.getPrimaryKeys(null, schemaName, tableName)) {
            while (rs.next()) {
                String pkName = rs.getString("PK_NAME");
                String colName = rs.getString("COLUMN_NAME");
                if (pkName == null) pkName = "PRIMARY";
                pkColumns.computeIfAbsent(pkName, k -> new ArrayList<>()).add(colName);
            }
        }

        for (Map.Entry<String, List<String>> entry : pkColumns.entrySet()) {
            SchemaObject pk = new SchemaObject(ObjectType.PRIMARY_KEY, entry.getKey(), schemaName, tableName);
            pk.setComment("Columns: " + String.join(", ", entry.getValue()));
            pks.add(pk);
        }

        return pks;
    }

    private List<SchemaObject> loadIndexes(DatabaseMetaData metaData, String schemaName, String tableName)
            throws SQLException {
        List<SchemaObject> indexes = new ArrayList<>();
        Map<String, IndexInfo> indexInfoMap = new LinkedHashMap<>();

        try (ResultSet rs = metaData.getIndexInfo(null, schemaName, tableName, false, true)) {
            while (rs.next()) {
                String indexName = rs.getString("INDEX_NAME");
                if (indexName == null) continue;
                boolean unique = !rs.getBoolean("NON_UNIQUE");
                String colName = rs.getString("COLUMN_NAME");
                String ascDesc = rs.getString("ASC_OR_DESC");

                IndexInfo info = indexInfoMap.computeIfAbsent(indexName,
                        k -> new IndexInfo(indexName, unique));
                info.columns.add(colName + (ascDesc != null ? " " + ascDesc : ""));
            }
        }

        for (IndexInfo info : indexInfoMap.values()) {
            if (info.unique) {
                SchemaObject uk = new SchemaObject(ObjectType.UNIQUE_KEY, info.name, schemaName, tableName);
                uk.setComment("Columns: " + String.join(", ", info.columns));
                indexes.add(uk);
            } else {
                SchemaObject idx = new SchemaObject(ObjectType.INDEX, info.name, schemaName, tableName);
                idx.setComment("Columns: " + String.join(", ", info.columns));
                indexes.add(idx);
            }
        }

        return indexes;
    }

    private static class IndexInfo {
        String name;
        boolean unique;
        List<String> columns = new ArrayList<>();

        IndexInfo(String name, boolean unique) {
            this.name = name;
            this.unique = unique;
        }
    }

    private List<SchemaObject> loadForeignKeys(DatabaseMetaData metaData, String schemaName, String tableName)
            throws SQLException {
        List<SchemaObject> fks = new ArrayList<>();
        Map<String, FKInfo> fkInfoMap = new LinkedHashMap<>();

        try (ResultSet rs = metaData.getImportedKeys(null, schemaName, tableName)) {
            while (rs.next()) {
                String fkName = rs.getString("FK_NAME");
                if (fkName == null) fkName = "FK_" + tableName;

                String fkCol = rs.getString("FKCOLUMN_NAME");
                String pkTable = rs.getString("PKTABLE_NAME");
                String pkCol = rs.getString("PKCOLUMN_NAME");

                FKInfo info = fkInfoMap.computeIfAbsent(fkName,
                        k -> new FKInfo(fkName, pkTable));
                info.columns.add(fkCol + " -> " + pkCol);
            }
        }

        for (FKInfo info : fkInfoMap.values()) {
            SchemaObject fk = new SchemaObject(ObjectType.FOREIGN_KEY, info.name, schemaName, tableName);
            fk.setComment("Ref: " + info.pkTable + " (" + String.join(", ", info.columns) + ")");
            fks.add(fk);
        }

        return fks;
    }

    private static class FKInfo {
        String name;
        String pkTable;
        List<String> columns = new ArrayList<>();

        FKInfo(String name, String pkTable) {
            this.name = name;
            this.pkTable = pkTable;
        }
    }

    private List<SchemaObject> loadTriggers(Connection conn, String schemaName, String tableName)
            throws SQLException {
        List<SchemaObject> triggers = new ArrayList<>();
        ConnectionType type = ConnectionType.MYSQL;

        try {
            type = ConnectionType.valueOf(conn.getMetaData().getDatabaseProductName().toUpperCase());
        } catch (Exception ignored) {
        }

        String sql = switch (type) {
            case MYSQL -> "SHOW TRIGGERS FROM `" + (schemaName != null ? schemaName : conn.getCatalog()) +
                    "` LIKE '" + tableName + "'";
            case POSTGRESQL -> "SELECT trigger_name FROM information_schema.triggers " +
                    "WHERE event_object_schema = '" + schemaName + "' AND event_object_table = '" + tableName + "'";
            case ORACLE -> "SELECT trigger_name FROM all_triggers " +
                    "WHERE owner = '" + schemaName + "' AND table_name = '" + tableName + "'";
            case SQL_SERVER -> "SELECT name FROM sys.triggers WHERE parent_id = OBJECT_ID('" +
                    (schemaName != null ? schemaName + "." : "") + tableName + "')";
            default -> null;
        };

        if (sql == null) return triggers;

        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                String triggerName = rs.getString(1);
                SchemaObject trigger = new SchemaObject(ObjectType.TRIGGER, triggerName, schemaName, tableName);
                triggers.add(trigger);
            }
        } catch (SQLException e) {
        }

        return triggers;
    }

    public Result<List<SchemaObject>> loadProcedures(String connectionId, String schemaName) {
        List<SchemaObject> procedures = new ArrayList<>();

        try (Connection conn = dataSourceRegistry.getConnection(connectionId)) {
            DatabaseMetaData metaData = conn.getMetaData();

            try (ResultSet rs = metaData.getProcedures(null, schemaName, "%")) {
                while (rs.next()) {
                    String procName = rs.getString("PROCEDURE_NAME");
                    String comment = rs.getString("REMARKS");

                    SchemaObject proc = new SchemaObject(ObjectType.PROCEDURE, procName, schemaName);
                    proc.setComment(comment);
                    procedures.add(proc);
                }
            }

            try (ResultSet rs = metaData.getFunctions(null, schemaName, "%")) {
                while (rs.next()) {
                    String funcName = rs.getString("FUNCTION_NAME");
                    String comment = rs.getString("REMARKS");

                    SchemaObject func = new SchemaObject(ObjectType.FUNCTION, funcName, schemaName);
                    func.setComment(comment);
                    procedures.add(func);
                }
            }

            Collections.sort(procedures, Comparator.comparing(SchemaObject::getName));
            return Result.success(procedures);
        } catch (SQLException e) {
            return Result.failure("加载存储过程失败: " + e.getMessage());
        }
    }

    public Result<String> generateDDL(String connectionId, SchemaObject object) {
        try (Connection conn = dataSourceRegistry.getConnection(connectionId)) {
            ConnectionType type = ConnectionType.MYSQL;
            try {
                type = ConnectionType.valueOf(conn.getMetaData().getDatabaseProductName().toUpperCase());
            } catch (Exception ignored) {
            }

            String ddl = switch (object.getType()) {
                case TABLE, VIEW -> generateTableDDL(conn, object, type);
                case PROCEDURE, FUNCTION -> generateProcedureDDL(conn, object, type);
                case TRIGGER -> generateTriggerDDL(conn, object, type);
                case INDEX, PRIMARY_KEY, UNIQUE_KEY -> generateIndexDDL(conn, object, type);
                default -> null;
            };

            if (ddl != null) {
                object.setDdl(ddl);
                return Result.success(ddl);
            }
            return Result.failure("不支持的对象类型: " + object.getType());
        } catch (SQLException e) {
            return Result.failure("生成DDL失败: " + e.getMessage());
        }
    }

    private String generateTableDDL(Connection conn, SchemaObject table, ConnectionType type)
            throws SQLException {
        String fullTableName = (table.getSchemaName() != null ? table.getSchemaName() + "." : "") + table.getName();
        StringBuilder ddl = new StringBuilder();

        ddl.append("CREATE TABLE ").append(fullTableName).append(" (\n");

        DatabaseMetaData metaData = conn.getMetaData();
        List<String> primaryKeys = new ArrayList<>();

        try (ResultSet pkRs = metaData.getPrimaryKeys(null, table.getSchemaName(), table.getName())) {
            while (pkRs.next()) {
                primaryKeys.add(pkRs.getString("COLUMN_NAME"));
            }
        }

        try (ResultSet colRs = metaData.getColumns(null, table.getSchemaName(), table.getName(), null)) {
            boolean firstCol = true;
            while (colRs.next()) {
                if (!firstCol) ddl.append(",\n");
                firstCol = false;

                String colName = colRs.getString("COLUMN_NAME");
                String typeName = colRs.getString("TYPE_NAME");
                int precision = colRs.getInt("COLUMN_SIZE");
                int scale = colRs.getInt("DECIMAL_DIGITS");
                boolean nullable = colRs.getInt("NULLABLE") == DatabaseMetaData.columnNullable;
                String defaultValue = colRs.getString("COLUMN_DEF");
                boolean autoIncrement = "YES".equalsIgnoreCase(colRs.getString("IS_AUTOINCREMENT"));

                ddl.append("  ").append(colName).append(" ").append(typeName);
                if (precision > 0 && !typeName.toUpperCase().contains("DATE")
                        && !typeName.toUpperCase().contains("TEXT")) {
                    ddl.append("(").append(precision);
                    if (scale > 0) ddl.append(",").append(scale);
                    ddl.append(")");
                }

                if (!nullable) ddl.append(" NOT NULL");
                if (defaultValue != null && !defaultValue.isEmpty()) {
                    ddl.append(" DEFAULT ").append(defaultValue);
                }
                if (autoIncrement) {
                    ddl.append(" ").append(type == ConnectionType.MYSQL ? "AUTO_INCREMENT" :
                            type == ConnectionType.POSTGRESQL ? "SERIAL" : "IDENTITY");
                }
            }
        }

        if (!primaryKeys.isEmpty()) {
            ddl.append(",\n  PRIMARY KEY (").append(String.join(", ", primaryKeys)).append(")");
        }

        ddl.append("\n)");

        if (table.getComment() != null && !table.getComment().isEmpty()) {
            ddl.append(" COMMENT '").append(table.getComment().replace("'", "''")).append("'");
        }

        ddl.append(";");
        return ddl.toString();
    }

    private String generateProcedureDDL(Connection conn, SchemaObject proc, ConnectionType type)
            throws SQLException {
        String sql = switch (type) {
            case MYSQL -> "SHOW CREATE PROCEDURE " + proc.getFullName();
            case POSTGRESQL -> "SELECT pg_get_functiondef(oid) FROM pg_proc " +
                    "WHERE proname = '" + proc.getName() + "'";
            case ORACLE -> "SELECT text FROM all_source WHERE type = 'PROCEDURE' " +
                    "AND name = '" + proc.getName() + "' ORDER BY line";
            default -> null;
        };

        if (sql == null) return null;

        StringBuilder ddl = new StringBuilder();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                ddl.append(rs.getString(2) != null ? rs.getString(2) : rs.getString(1));
                ddl.append("\n");
            }
        } catch (SQLException e) {
            return "CREATE PROCEDURE " + proc.getFullName() + "()\nBEGIN\n  -- 定义\nEND;";
        }

        return ddl.length() > 0 ? ddl.toString() :
                "CREATE PROCEDURE " + proc.getFullName() + "()\nBEGIN\n  -- 定义\nEND;";
    }

    private String generateTriggerDDL(Connection conn, SchemaObject trigger, ConnectionType type)
            throws SQLException {
        String sql = switch (type) {
            case MYSQL -> "SHOW CREATE TRIGGER " + trigger.getFullName();
            case POSTGRESQL -> "SELECT pg_get_triggerdef(oid) FROM pg_trigger " +
                    "WHERE tgname = '" + trigger.getName() + "'";
            default -> null;
        };

        if (sql == null) {
            return "CREATE TRIGGER " + trigger.getFullName() + "\n" +
                    "BEFORE INSERT ON " + trigger.getParentName() + "\n" +
                    "FOR EACH ROW\nBEGIN\n  -- 逻辑\nEND;";
        }

        StringBuilder ddl = new StringBuilder();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            if (rs.next()) {
                ddl.append(rs.getString(2) != null ? rs.getString(2) : rs.getString(1));
            }
        }

        return ddl.length() > 0 ? ddl.toString() :
                "CREATE TRIGGER " + trigger.getFullName() + "\nBEFORE INSERT ON " +
                        trigger.getParentName() + "\nFOR EACH ROW\nBEGIN\n  -- 逻辑\nEND;";
    }

    private String generateIndexDDL(Connection conn, SchemaObject index, ConnectionType type) {
        boolean isUnique = index.getType() == ObjectType.UNIQUE_KEY || index.getType() == ObjectType.PRIMARY_KEY;
        String tableName = (index.getSchemaName() != null ? index.getSchemaName() + "." : "") + index.getParentName();

        StringBuilder ddl = new StringBuilder("CREATE ");
        if (isUnique) ddl.append("UNIQUE ");
        ddl.append("INDEX ").append(index.getName()).append(" ON ").append(tableName);

        if (index.getComment() != null && index.getComment().startsWith("Columns: ")) {
            String columns = index.getComment().substring("Columns: ".length());
            ddl.append(" (").append(columns).append(")");
        }

        ddl.append(";");
        return ddl.toString();
    }

    public Result<List<String>> compareDDL(String ddl1, String ddl2) {
        List<String> differences = new ArrayList<>();

        String[] lines1 = ddl1.split("\n");
        String[] lines2 = ddl2.split("\n");

        Set<String> set1 = new LinkedHashSet<>(Arrays.asList(lines1));
        Set<String> set2 = new LinkedHashSet<>(Arrays.asList(lines2));

        for (String line : lines1) {
            if (!set2.contains(line) && !line.trim().isEmpty()) {
                differences.add("- " + line);
            }
        }

        for (String line : lines2) {
            if (!set1.contains(line) && !line.trim().isEmpty()) {
                differences.add("+ " + line);
            }
        }

        return Result.success(differences);
    }

    public void loadSchemasAsync(String connectionId, Consumer<Result<List<SchemaObject>>> callback) {
        ApplicationContext.executeAsync(() -> {
            Result<List<SchemaObject>> result = loadSchemas(connectionId);
            Platform.runLater(() -> callback.accept(result));
        });
    }

    public void loadTablesAsync(String connectionId, String schemaName,
                                Consumer<Result<List<SchemaObject>>> callback) {
        ApplicationContext.executeAsync(() -> {
            Result<List<SchemaObject>> result = loadTables(connectionId, schemaName);
            Platform.runLater(() -> callback.accept(result));
        });
    }

    public void loadTableChildrenAsync(String connectionId, SchemaObject table,
                                       Consumer<Result<List<SchemaObject>>> callback) {
        ApplicationContext.executeAsync(() -> {
            Result<List<SchemaObject>> result = loadTableChildren(connectionId, table);
            Platform.runLater(() -> callback.accept(result));
        });
    }

    public void loadProceduresAsync(String connectionId, String schemaName,
                                    Consumer<Result<List<SchemaObject>>> callback) {
        ApplicationContext.executeAsync(() -> {
            Result<List<SchemaObject>> result = loadProcedures(connectionId, schemaName);
            Platform.runLater(() -> callback.accept(result));
        });
    }

    public void generateDDLAsync(String connectionId, SchemaObject object,
                                 Consumer<Result<String>> callback) {
        ApplicationContext.executeAsync(() -> {
            Result<String> result = generateDDL(connectionId, object);
            Platform.runLater(() -> callback.accept(result));
        });
    }
}
