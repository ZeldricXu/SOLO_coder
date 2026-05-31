package com.cdcsync.metadata.crawler;

import com.alibaba.fastjson2.JSON;
import com.cdcsync.common.exception.BusinessException;
import com.cdcsync.common.spi.SchemaCrawler;
import com.cdcsync.common.util.ValidationUtils;
import com.cdcsync.metadata.domain.DataSource;
import com.cdcsync.metadata.domain.SchemaInfo;
import com.cdcsync.metadata.domain.TableInfo;
import com.cdcsync.metadata.mapper.TableInfoMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
abstract class AbstractSchemaCrawler implements SchemaCrawler {

    private static final int DEFAULT_SAMPLE_SIZE = 100;
    private static final int MAX_SAMPLE_SIZE = 1000;
    private static final int MAX_RETRY_ATTEMPTS = 2;

    protected final DataSource dataSource;
    protected final JdbcMetadataProvider metadataProvider;
    protected final StatisticsCalculator statisticsCalculator;
    protected final TableInfoMapper tableInfoMapper;

    protected AbstractSchemaCrawler(DataSource dataSource, TableInfoMapper tableInfoMapper) {
        ValidationUtils.notNull(dataSource, "dataSource");
        ValidationUtils.notBlank(dataSource.getId(), "dataSource.id");
        ValidationUtils.notNull(tableInfoMapper, "tableInfoMapper");

        this.dataSource = dataSource;
        this.metadataProvider = new JdbcMetadataProvider(dataSource);
        this.statisticsCalculator = new StatisticsCalculator();
        this.tableInfoMapper = tableInfoMapper;
    }

    @Override
    public SchemaInfo crawlSchema(String dataSourceId) {
        ValidationUtils.notBlank(dataSourceId, "dataSourceId");
        log.info("Starting schema crawl for data source: {}", dataSourceId);

        SchemaInfo schemaInfo = new SchemaInfo();
        schemaInfo.setDataSourceId(dataSourceId);
        schemaInfo.setSchemaName(ValidationUtils.safeTrim(dataSource.getDatabaseName()));
        schemaInfo.setLastCrawledAt(LocalDateTime.now());

        Connection conn = null;
        try {
            conn = metadataProvider.getConnection();
            List<String> tableNames = metadataProvider.listTableNames(conn);
            schemaInfo.setTableCount(tableNames.size());

            Map<String, Object> metadata = new HashMap<>();
            metadata.put("tables", tableNames);
            metadata.put("databaseType", dataSource.getType());
            schemaInfo.setMetadataJson(JSON.toJSONString(metadata));

            log.info("Schema crawl completed. Found {} tables for data source: {}", tableNames.size(), dataSourceId);
            return schemaInfo;
        } catch (Exception e) {
            log.error("Failed to crawl schema for data source: {}", dataSourceId, e);
            throw new BusinessException("Failed to crawl schema: " + e.getMessage());
        } finally {
            JdbcMetadataProvider.closeQuietly(conn);
        }
    }

    @Override
    public List<TableInfo> listTables(String dataSourceId) {
        ValidationUtils.notBlank(dataSourceId, "dataSourceId");
        log.info("Listing tables for data source: {}", dataSourceId);

        List<TableInfo> tables = new ArrayList<>();
        Connection conn = null;

        try {
            conn = metadataProvider.getConnection();
            List<String> tableNames = metadataProvider.listTableNames(conn);

            for (String tableName : tableNames) {
                TableInfo tableInfo = new TableInfo();
                tableInfo.setDataSourceId(dataSourceId);
                tableInfo.setSchemaName(ValidationUtils.safeTrim(dataSource.getDatabaseName()));
                tableInfo.setTableName(ValidationUtils.safeTruncate(tableName, 128));
                tables.add(tableInfo);
            }

            log.info("Found {} tables for data source: {}", tables.size(), dataSourceId);
            return tables;
        } catch (Exception e) {
            log.error("Failed to list tables for data source: {}", dataSourceId, e);
            throw new BusinessException("Failed to list tables: " + e.getMessage());
        } finally {
            JdbcMetadataProvider.closeQuietly(conn);
        }
    }

    @Override
    @Transactional
    public TableInfo getTableInfo(String dataSourceId, String tableName) {
        ValidationUtils.notBlank(dataSourceId, "dataSourceId");
        ValidationUtils.notBlank(tableName, "tableName");
        log.info("Getting table info for {}.{}", dataSourceId, tableName);

        Connection conn = null;
        try {
            conn = metadataProvider.getConnection();

            TableInfo tableInfo = new TableInfo();
            tableInfo.setDataSourceId(dataSourceId);
            tableInfo.setSchemaName(ValidationUtils.safeTrim(dataSource.getDatabaseName()));
            tableInfo.setTableName(ValidationUtils.safeTruncate(tableName, 128));
            tableInfo.setColumnsJson(metadataProvider.getColumnsJson(conn, tableName));
            tableInfo.setIndexesJson(metadataProvider.getIndexesJson(conn, tableName));
            tableInfo.setRowCount(Math.max(0, metadataProvider.getRowCount(conn, tableName)));
            tableInfo.setSizeBytes(Math.max(0, metadataProvider.getTableSize(conn, tableName)));

            int sampleLimit = Math.min(DEFAULT_SAMPLE_SIZE, MAX_SAMPLE_SIZE);
            List<Map<String, Object>> sampleData;
            try {
                sampleData = metadataProvider.getSampleData(conn, tableName, sampleLimit);
                tableInfo.setSampleDataJson(JSON.toJSONString(sampleData));
            } catch (Exception e) {
                log.warn("Failed to get sample data for table {}: {}", tableName, e.getMessage());
                sampleData = List.of();
                tableInfo.setSampleDataJson("[]");
            }

            try {
                tableInfo.setStatisticsJson(statisticsCalculator.calculateStatisticsJson(sampleData, tableInfo.getColumnsJson()));
            } catch (Exception e) {
                log.warn("Failed to calculate statistics for table {}: {}", tableName, e.getMessage());
                tableInfo.setStatisticsJson("{}");
            }

            tableInfo.setLastAnalyzedAt(LocalDateTime.now());

            log.info("Table info retrieved for {}.{}", dataSourceId, tableName);
            return tableInfo;
        } catch (Exception e) {
            log.error("Failed to get table info for {}.{}", dataSourceId, tableName, e);
            throw new BusinessException("Failed to get table info: " + e.getMessage());
        } finally {
            JdbcMetadataProvider.closeQuietly(conn);
        }
    }

    @Override
    public Map<String, Object> getTableStatistics(String dataSourceId, String tableName) {
        ValidationUtils.notBlank(dataSourceId, "dataSourceId");
        ValidationUtils.notBlank(tableName, "tableName");
        log.info("Getting table statistics for {}.{}", dataSourceId, tableName);

        Connection conn = null;
        try {
            conn = metadataProvider.getConnection();

            long rowCount = metadataProvider.getRowCount(conn, tableName);
            long sizeBytes = metadataProvider.getTableSize(conn, tableName);
            String columnsJson = metadataProvider.getColumnsJson(conn, tableName);

            List<Map<String, Object>> sampleData;
            try {
                sampleData = metadataProvider.getSampleData(conn, tableName, DEFAULT_SAMPLE_SIZE);
            } catch (Exception e) {
                log.warn("Failed to get sample data for statistics {}: {}", tableName, e.getMessage());
                sampleData = List.of();
            }

            String statisticsJson;
            try {
                statisticsJson = statisticsCalculator.calculateStatisticsJson(sampleData, columnsJson);
            } catch (Exception e) {
                log.warn("Failed to calculate statistics for {}: {}", tableName, e.getMessage());
                statisticsJson = "{}";
            }

            return statisticsCalculator.buildTableStatistics(rowCount, sizeBytes, statisticsJson);
        } catch (Exception e) {
            log.error("Failed to get table statistics for {}.{}", dataSourceId, tableName, e);
            throw new BusinessException("Failed to get table statistics: " + e.getMessage());
        } finally {
            JdbcMetadataProvider.closeQuietly(conn);
        }
    }

    @Override
    public List<Map<String, Object>> getSampleData(String dataSourceId, String tableName, int limit) {
        ValidationUtils.notBlank(dataSourceId, "dataSourceId");
        ValidationUtils.notBlank(tableName, "tableName");

        int safeLimit = ValidationUtils.validLimit(limit);
        log.info("Getting sample data for {}.{} with limit {}", dataSourceId, tableName, safeLimit);

        Connection conn = null;
        try {
            conn = metadataProvider.getConnection();
            return metadataProvider.getSampleData(conn, tableName, safeLimit);
        } catch (Exception e) {
            log.error("Failed to get sample data for {}.{}", dataSourceId, tableName, e);
            throw new BusinessException("Failed to get sample data: " + e.getMessage());
        } finally {
            JdbcMetadataProvider.closeQuietly(conn);
        }
    }

    protected abstract String getDatabaseType();
}
