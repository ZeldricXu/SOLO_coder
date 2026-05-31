package com.streamsql.modules.metadata_crawler;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.streamsql.common.PageResult;
import com.streamsql.dto.DatasourceDTO;
import com.streamsql.entity.DatasourceInfo;
import com.streamsql.entity.MetadataSchema;
import com.streamsql.entity.MetadataStatistics;
import com.streamsql.entity.SampleData;
import com.streamsql.mapper.DatasourceInfoMapper;
import com.streamsql.mapper.MetadataSchemaMapper;
import com.streamsql.mapper.MetadataStatisticsMapper;
import com.streamsql.mapper.SampleDataMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class MetadataCrawlerService {

    private final DatasourceInfoMapper datasourceInfoMapper;
    private final MetadataSchemaMapper metadataSchemaMapper;
    private final MetadataStatisticsMapper metadataStatisticsMapper;
    private final SampleDataMapper sampleDataMapper;
    private final ObjectMapper objectMapper;

    @Transactional(rollbackFor = Exception.class)
    public DatasourceInfo createDatasource(DatasourceDTO dto) throws JsonProcessingException {
        DatasourceInfo datasource = new DatasourceInfo();
        datasource.setDatasourceName(dto.getDatasourceName());
        datasource.setDatasourceType(dto.getDatasourceType());
        datasource.setConnectionConfig(objectMapper.writeValueAsString(dto.getConnectionConfig()));
        datasource.setStatus("active");

        datasourceInfoMapper.insert(datasource);
        return datasource;
    }

    @Transactional(rollbackFor = Exception.class)
    public DatasourceInfo updateDatasource(String datasourceId, DatasourceDTO dto) throws JsonProcessingException {
        DatasourceInfo datasource = datasourceInfoMapper.selectById(datasourceId);
        if (datasource == null) {
            throw new IllegalArgumentException("数据源不存在: " + datasourceId);
        }

        datasource.setDatasourceName(dto.getDatasourceName());
        datasource.setDatasourceType(dto.getDatasourceType());
        datasource.setConnectionConfig(objectMapper.writeValueAsString(dto.getConnectionConfig()));

        datasourceInfoMapper.updateById(datasource);
        return datasource;
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteDatasource(String datasourceId) {
        datasourceInfoMapper.deleteById(datasourceId);

        metadataSchemaMapper.delete(new LambdaQueryWrapper<MetadataSchema>()
                .eq(MetadataSchema::getDatasourceId, datasourceId));
        metadataStatisticsMapper.delete(new LambdaQueryWrapper<MetadataStatistics>()
                .eq(MetadataStatistics::getDatasourceId, datasourceId));
        sampleDataMapper.delete(new LambdaQueryWrapper<SampleData>()
                .eq(SampleData::getDatasourceId, datasourceId));
    }

    public DatasourceInfo getDatasource(String datasourceId) {
        return datasourceInfoMapper.selectById(datasourceId);
    }

    public PageResult<DatasourceInfo> listDatasources(int page, int size, String datasourceType, String status) {
        LambdaQueryWrapper<DatasourceInfo> wrapper = new LambdaQueryWrapper<>();
        if (datasourceType != null) {
            wrapper.eq(DatasourceInfo::getDatasourceType, datasourceType);
        }
        if (status != null) {
            wrapper.eq(DatasourceInfo::getStatus, status);
        }
        wrapper.orderByDesc(DatasourceInfo::getCreatedAt);

        IPage<DatasourceInfo> pageResult = datasourceInfoMapper.selectPage(new Page<>(page, size), wrapper);
        return PageResult.of(pageResult.getRecords(), pageResult.getTotal(), page, size);
    }

    @Async
    @Transactional(rollbackFor = Exception.class)
    public void crawlMetadataAsync(String datasourceId) {
        try {
            crawlMetadata(datasourceId);
        } catch (Exception e) {
            log.error("Metadata crawl failed for datasource: {}", datasourceId, e);
            DatasourceInfo datasource = datasourceInfoMapper.selectById(datasourceId);
            if (datasource != null) {
                datasource.setStatus("error");
                datasourceInfoMapper.updateById(datasource);
            }
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void crawlMetadata(String datasourceId) throws JsonProcessingException {
        DatasourceInfo datasource = datasourceInfoMapper.selectById(datasourceId);
        if (datasource == null) {
            throw new IllegalArgumentException("数据源不存在: " + datasourceId);
        }

        log.info("Starting metadata crawl for datasource: {}", datasource.getDatasourceName());
        datasource.setStatus("crawling");
        datasourceInfoMapper.updateById(datasource);

        Map<String, Object> config = objectMapper.readValue(datasource.getConnectionConfig(), Map.class);

        try (Connection conn = getConnection(datasource.getDatasourceType(), config)) {
            DatabaseMetaData metaData = conn.getMetaData();

            String catalog = (String) config.get("database");
            String schemaPattern = (String) config.get("schema");

            try (ResultSet tables = metaData.getTables(catalog, schemaPattern, "%", new String[]{"TABLE", "VIEW"})) {
                while (tables.next()) {
                    String tableCat = tables.getString("TABLE_CAT");
                    String tableSchem = tables.getString("TABLE_SCHEM");
                    String tableName = tables.getString("TABLE_NAME");

                    extractTableSchema(datasourceId, metaData, tableCat, tableSchem, tableName);
                    extractTableStatistics(datasourceId, conn, tableCat, tableSchem, tableName);
                    extractSampleData(datasourceId, conn, tableCat, tableSchem, tableName);
                }
            }

            datasource.setStatus("active");
            datasource.setLastCrawlTime(LocalDateTime.now());
            datasourceInfoMapper.updateById(datasource);

            log.info("Metadata crawl completed for datasource: {}", datasource.getDatasourceName());
        } catch (SQLException e) {
            log.error("Failed to crawl metadata", e);
            datasource.setStatus("error");
            datasourceInfoMapper.updateById(datasource);
            throw new RuntimeException("元数据爬取失败: " + e.getMessage(), e);
        }
    }

    private Connection getConnection(String datasourceType, Map<String, Object> config) throws SQLException {
        String jdbcUrl;
        String driverClass;

        switch (datasourceType.toLowerCase()) {
            case "mysql":
                driverClass = "com.mysql.cj.jdbc.Driver";
                jdbcUrl = String.format("jdbc:mysql://%s:%s/%s?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai",
                        config.getOrDefault("host", "localhost"),
                        config.getOrDefault("port", "3306"),
                        config.getOrDefault("database", ""));
                break;
            case "postgresql":
                driverClass = "org.postgresql.Driver";
                jdbcUrl = String.format("jdbc:postgresql://%s:%s/%s",
                        config.getOrDefault("host", "localhost"),
                        config.getOrDefault("port", "5432"),
                        config.getOrDefault("database", ""));
                break;
            default:
                throw new IllegalArgumentException("不支持的数据源类型: " + datasourceType);
        }

        try {
            Class.forName(driverClass);
        } catch (ClassNotFoundException e) {
            log.warn("Driver class not found: {}", driverClass);
        }

        return DriverManager.getConnection(jdbcUrl,
                (String) config.get("username"),
                (String) config.get("password"));
    }

    private void extractTableSchema(String datasourceId, DatabaseMetaData metaData,
                                         String catalog, String schema, String tableName) throws SQLException {
        metadataSchemaMapper.delete(new LambdaQueryWrapper<MetadataSchema>()
                .eq(MetadataSchema::getDatasourceId, datasourceId)
                .eq(MetadataSchema::getSchemaName, schema != null ? schema : catalog)
                .eq(MetadataSchema::getTableName, tableName));

        try (ResultSet columns = metaData.getColumns(catalog, schema, tableName, null)) {
            while (columns.next()) {
                MetadataSchema columnSchema = new MetadataSchema();
                columnSchema.setDatasourceId(datasourceId);
                columnSchema.setSchemaName(schema != null ? schema : catalog);
                columnSchema.setTableName(tableName);
                columnSchema.setColumnName(columns.getString("COLUMN_NAME"));
                columnSchema.setDataType(columns.getString("TYPE_NAME"));
                columnSchema.setNullable(columns.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls);
                columnSchema.setPrimaryKey(false);
                columnSchema.setColumnComment(columns.getString("REMARKS"));

                metadataSchemaMapper.insert(columnSchema);
            }
        }

        try (ResultSet primaryKeys = metaData.getPrimaryKeys(catalog, schema, tableName)) {
            while (primaryKeys.next()) {
                String pkColumnName = primaryKeys.getString("COLUMN_NAME");
                List<MetadataSchema> columns = metadataSchemaMapper.selectList(
                        new LambdaQueryWrapper<MetadataSchema>()
                                .eq(MetadataSchema::getDatasourceId, datasourceId)
                                .eq(MetadataSchema::getSchemaName, schema != null ? schema : catalog)
                                .eq(MetadataSchema::getTableName, tableName)
                                .eq(MetadataSchema::getColumnName, pkColumnName)
                );
                for (MetadataSchema column : columns) {
                    column.setPrimaryKey(true);
                    metadataSchemaMapper.updateById(column);
                }
            }
        }
    }

    private void extractTableStatistics(String datasourceId, Connection conn,
                                         String catalog, String schema, String tableName) throws SQLException, JsonProcessingException {
        String fullTableName = (catalog != null ? catalog + "." : "") +
                (schema != null ? schema + "." : "") + tableName;

        metadataStatisticsMapper.delete(new LambdaQueryWrapper<MetadataStatistics>()
                .eq(MetadataStatistics::getDatasourceId, datasourceId)
                .eq(MetadataStatistics::getSchemaName, schema != null ? schema : catalog)
                .eq(MetadataStatistics::getTableName, tableName));

        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) as row_count FROM " + fullTableName + " LIMIT 1")) {
            if (rs.next()) {
                MetadataStatistics stat = new MetadataStatistics();
                stat.setDatasourceId(datasourceId);
                stat.setSchemaName(schema != null ? schema : catalog);
                stat.setTableName(tableName);
                stat.setStatType("ROW_COUNT");
                stat.setStatValue(rs.getDouble("row_count"));
                stat.setStatTime(LocalDateTime.now());
                metadataStatisticsMapper.insert(stat);
            }
        } catch (SQLException e) {
            log.warn("Failed to get row count for table: {}", fullTableName, e);
        }

        List<MetadataSchema> columns = metadataSchemaMapper.selectList(
                new LambdaQueryWrapper<MetadataSchema>()
                        .eq(MetadataSchema::getDatasourceId, datasourceId)
                        .eq(MetadataSchema::getSchemaName, schema != null ? schema : catalog)
                        .eq(MetadataSchema::getTableName, tableName)
        );

        for (MetadataSchema column : columns) {
            String colName = column.getColumnName();
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                         "SELECT COUNT(DISTINCT " + colName + ") as distinct_count, " +
                                 "COUNT(*) as total_count FROM " + fullTableName + " LIMIT 1")) {
                if (rs.next()) {
                    MetadataStatistics distinctStat = new MetadataStatistics();
                    distinctStat.setDatasourceId(datasourceId);
                    distinctStat.setSchemaName(schema != null ? schema : catalog);
                    distinctStat.setTableName(tableName);
                    distinctStat.setColumnName(colName);
                    distinctStat.setStatType("DISTINCT_COUNT");
                    distinctStat.setStatValue(rs.getDouble("distinct_count"));
                    distinctStat.setStatTime(LocalDateTime.now());
                    metadataStatisticsMapper.insert(distinctStat);
                }
            } catch (SQLException e) {
                log.warn("Failed to get statistics for column: {}.{}", fullTableName, colName, e);
            }
        }
    }

    private void extractSampleData(String datasourceId, Connection conn,
                                    String catalog, String schema, String tableName) throws SQLException, JsonProcessingException {
        String fullTableName = (catalog != null ? catalog + "." : "") +
                (schema != null ? schema + "." : "") + tableName;

        sampleDataMapper.delete(new LambdaQueryWrapper<SampleData>()
                .eq(SampleData::getDatasourceId, datasourceId)
                .eq(SampleData::getSchemaName, schema != null ? schema : catalog)
                .eq(SampleData::getTableName, tableName));

        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM " + fullTableName + " LIMIT 10")) {
            ResultSetMetaData rsmd = rs.getMetaData();
            int columnCount = rsmd.getColumnCount();

            while (rs.next()) {
                Map<String, Object> rowData = new LinkedHashMap<>();
                for (int i = 1; i <= columnCount; i++) {
                    rowData.put(rsmd.getColumnName(i), rs.getObject(i));
                }

                SampleData sampleData = new SampleData();
                sampleData.setDatasourceId(datasourceId);
                sampleData.setSchemaName(schema != null ? schema : catalog);
                sampleData.setTableName(tableName);
                sampleData.setSampleData(objectMapper.writeValueAsString(rowData));
                sampleData.setSampleTime(LocalDateTime.now());

                sampleDataMapper.insert(sampleData);
            }
        } catch (SQLException e) {
            log.warn("Failed to get sample data for table: {}", fullTableName, e);
        }
    }

    public List<MetadataSchema> getTableSchema(String datasourceId, String schemaName, String tableName) {
        return metadataSchemaMapper.selectList(new LambdaQueryWrapper<MetadataSchema>()
                .eq(MetadataSchema::getDatasourceId, datasourceId)
                .eq(schemaName != null, MetadataSchema::getSchemaName, schemaName)
                .eq(tableName != null, MetadataSchema::getTableName, tableName));
    }

    public List<MetadataStatistics> getTableStatistics(String datasourceId, String schemaName, String tableName) {
        return metadataStatisticsMapper.selectList(new LambdaQueryWrapper<MetadataStatistics>()
                .eq(MetadataStatistics::getDatasourceId, datasourceId)
                .eq(schemaName != null, MetadataStatistics::getSchemaName, schemaName)
                .eq(tableName != null, MetadataStatistics::getTableName, tableName)
                .orderByDesc(MetadataStatistics::getStatTime));
    }

    public List<SampleData> getSampleData(String datasourceId, String schemaName, String tableName, int limit) {
        return sampleDataMapper.selectList(new LambdaQueryWrapper<SampleData>()
                .eq(SampleData::getDatasourceId, datasourceId)
                .eq(schemaName != null, SampleData::getSchemaName, schemaName)
                .eq(tableName != null, SampleData::getTableName, tableName)
                .orderByDesc(SampleData::getSampleTime)
                .last("LIMIT " + limit));
    }

    public boolean testConnection(DatasourceDTO dto) {
        try {
            Map<String, Object> config = dto.getConnectionConfig();
            Connection conn = getConnection(dto.getDatasourceType(), config);
            conn.close();
            return true;
        } catch (Exception e) {
            log.error("Connection test failed", e);
            return false;
        }
    }
}
