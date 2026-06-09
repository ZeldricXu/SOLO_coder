package com.company.dbstudio.etl.exporter;

import com.company.dbstudio.connection.ConnectionManager;
import com.company.dbstudio.connection.model.ConnectionConfig;
import com.company.dbstudio.etl.model.Format;
import com.company.dbstudio.etl.model.ImportExportConfig;
import com.company.dbstudio.etl.model.ProgressInfo;
import com.company.dbstudio.result.Result;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public abstract class AbstractExporter implements Closeable {

    protected static final Logger logger = LoggerFactory.getLogger(AbstractExporter.class);

    protected final AtomicBoolean cancelled = new AtomicBoolean(false);
    protected volatile int progress = 0;
    protected volatile long rowCount = 0;
    protected ImportExportConfig config;
    protected Connection connection;
    protected HikariDataSource dataSource;
    protected Statement statement;
    protected ResultSet resultSet;
    protected ResultSetMetaData metaData;
    protected List<Map<String, Object>> columnMappings;
    protected List<String> columnNames;
    protected List<Integer> columnTypes;

    public final Result<Long> export(ImportExportConfig config, Consumer<ProgressInfo> progressCallback) {
        this.config = config;
        this.progress = 0;
        this.rowCount = 0;
        this.cancelled.set(false);

        try {
            openConnection();
            buildColumnMappings();
            openOutputStream();
            writeHeader();
            processRows(progressCallback);
            writeFooter();
            flushAndClose();
            closeResources();

            if (cancelled.get()) {
                return Result.ok(rowCount, "Export cancelled after " + rowCount + " rows");
            }
            return Result.ok(rowCount, "Successfully exported " + rowCount + " rows");
        } catch (Exception e) {
            logger.error("Export failed", e);
            closeResources();
            return Result.err("Export failed: " + e.getMessage());
        }
    }

    protected void openConnection() throws Exception {
        if (config.getConnectionId() != null) {
            ConnectionConfig connectionConfig = ConnectionManager.getInstance()
                    .getConnectionRepository()
                    .getConnection(config.getConnectionId());

            if (connectionConfig == null) {
                throw new IllegalArgumentException("Connection not found: " + config.getConnectionId());
            }

            dataSource = ConnectionManager.getInstance().createDataSource(connectionConfig);
            connection = dataSource.getConnection();
            statement = connection.createStatement(
                    ResultSet.TYPE_FORWARD_ONLY,
                    ResultSet.CONCUR_READ_ONLY
            );
            statement.setFetchSize(config.getFetchSize());

            if (config.getQuery() != null && !config.getQuery().isEmpty()) {
                resultSet = statement.executeQuery(config.getQuery());
            } else if (config.getTableName() != null && !config.getTableName().isEmpty()) {
                String query = "SELECT * FROM " + config.getTableName();
                resultSet = statement.executeQuery(query);
            } else {
                throw new IllegalArgumentException("Either query or tableName must be specified");
            }

            metaData = resultSet.getMetaData();
            logger.info("Database connection opened for export");
        }
    }

    protected void buildColumnMappings() throws Exception {
        columnMappings = new ArrayList<>();
        columnNames = new ArrayList<>();
        columnTypes = new ArrayList<>();

        if (metaData != null) {
            int columnCount = metaData.getColumnCount();
            for (int i = 1; i <= columnCount; i++) {
                Map<String, Object> mapping = new LinkedHashMap<>();
                String columnName = metaData.getColumnName(i);
                int columnType = metaData.getColumnType(i);

                mapping.put("index", i);
                mapping.put("name", columnName);
                mapping.put("type", columnType);
                mapping.put("typeName", metaData.getColumnTypeName(i));
                mapping.put("export", true);

                columnMappings.add(mapping);
                columnNames.add(columnName);
                columnTypes.add(columnType);
            }
        } else if (config.getColumnMappings() != null) {
            for (var mapping : config.getColumnMappings()) {
                columnMappings.add(mapping);
                columnNames.add((String) mapping.get("name"));
                columnTypes.add((Integer) mapping.getOrDefault("type", 12));
            }
        }

        logger.info("Built column mappings for {} columns", columnNames.size());
    }

    protected void processRows(Consumer<ProgressInfo> progressCallback) throws Exception {
        if (resultSet == null) {
            return;
        }

        int batchSize = config.getBatchSize();
        int processedInBatch = 0;

        while (resultSet.next() && !cancelled.get()) {
            writeRow();
            rowCount++;
            processedInBatch++;

            if (processedInBatch >= batchSize) {
                flushBatch();
                processedInBatch = 0;
            }

            if (progressCallback != null && rowCount % 1000 == 0) {
                updateProgress(progressCallback);
            }
        }

        if (processedInBatch > 0) {
            flushBatch();
        }

        updateProgress(progressCallback);
    }

    protected void updateProgress(Consumer<ProgressInfo> progressCallback) {
        if (progressCallback != null) {
            int progressPercent = config.getTotalRows() > 0
                    ? (int) ((rowCount * 100) / config.getTotalRows())
                    : (int) Math.min(progress + 1, 99);
            progress = progressPercent;
            progressCallback.accept(new ProgressInfo(progressPercent, rowCount, "Exporting..."));
        }
    }

    protected abstract void openOutputStream() throws Exception;

    protected abstract void writeHeader() throws Exception;

    protected abstract void writeRow() throws Exception;

    protected abstract void writeFooter() throws Exception;

    protected abstract void flushBatch() throws Exception;

    protected abstract void flushAndClose() throws Exception;

    @Override
    public void close() {
        closeResources();
    }

    protected void closeResources() {
        try {
            if (resultSet != null) {
                resultSet.close();
            }
        } catch (Exception e) {
            logger.warn("Error closing ResultSet", e);
        }
        try {
            if (statement != null) {
                statement.close();
            }
        } catch (Exception e) {
            logger.warn("Error closing Statement", e);
        }
        try {
            if (connection != null) {
                connection.close();
            }
        } catch (Exception e) {
            logger.warn("Error closing Connection", e);
        }
        try {
            if (dataSource != null) {
                dataSource.close();
            }
        } catch (Exception e) {
            logger.warn("Error closing DataSource", e);
        }
        resultSet = null;
        statement = null;
        connection = null;
        dataSource = null;
    }

    public void cancel() {
        cancelled.set(true);
        logger.info("Export cancelled");
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    public int getProgress() {
        return progress;
    }

    public void setProgress(int progress) {
        this.progress = progress;
    }

    public ImportExportConfig getConfig() {
        return config;
    }

    public void setConfig(ImportExportConfig config) {
        this.config = config;
    }

    protected Object getColumnValue(int columnIndex) throws Exception {
        if (resultSet != null) {
            return resultSet.getObject(columnIndex);
        }
        return null;
    }

    protected List<Map<String, Object>> getColumnMappings() {
        return columnMappings;
    }

    protected List<String> getColumnNames() {
        return columnNames;
    }

    protected List<Integer> getColumnTypes() {
        return columnTypes;
    }

    public Format getFormat() {
        return config != null ? config.getFormat() : null;
    }
}
