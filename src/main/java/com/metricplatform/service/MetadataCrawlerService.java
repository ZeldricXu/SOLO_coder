package com.metricplatform.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.metricplatform.entity.SysMetadataSchema;
import com.metricplatform.entity.SysMetadataSource;
import com.metricplatform.mapper.SysMetadataSchemaMapper;
import com.metricplatform.mapper.SysMetadataSourceMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class MetadataCrawlerService extends ServiceImpl<SysMetadataSourceMapper, SysMetadataSource> {

    private final SysMetadataSchemaMapper schemaMapper;

    @Scheduled(fixedRate = 60000)
    public void scheduledScan() {
        LocalDateTime now = LocalDateTime.now();
        List<SysMetadataSource> sources = this.list(new LambdaQueryWrapper<SysMetadataSource>()
                .eq(SysMetadataSource::getStatus, "active"));

        for (SysMetadataSource source : sources) {
            if (source.getLastScanAt() == null ||
                    java.time.Duration.between(source.getLastScanAt(), now).toMillis() >= source.getScanInterval()) {
                try {
                    scanSourceAsync(source);
                } catch (Exception e) {
                    log.error("定时扫描数据源失败: {}", source.getSourceName(), e);
                }
            }
        }
    }

    @Async("crawlerExecutor")
    public void scanSourceAsync(SysMetadataSource source) {
        scanSource(source);
    }

    @Transactional(rollbackFor = Exception.class)
    public SysMetadataSource createSource(String sourceName, String sourceType,
                                          Map<String, Object> connectionConfig, Long scanInterval) {
        SysMetadataSource source = new SysMetadataSource();
        source.setSourceId("src_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        source.setSourceName(sourceName);
        source.setSourceType(sourceType.toLowerCase());
        source.setConnectionConfig(connectionConfig);
        source.setStatus("active");
        source.setScanInterval(scanInterval != null ? scanInterval : 86400000L);

        this.save(source);
        log.info("已创建元数据数据源: {} (类型: {})", sourceName, sourceType);
        return source;
    }

    @Transactional(rollbackFor = Exception.class)
    public List<SysMetadataSchema> scanSource(SysMetadataSource source) {
        log.info("开始扫描数据源: {} (类型: {})", source.getSourceName(), source.getSourceType());
        List<SysMetadataSchema> schemas = new ArrayList<>();

        JdbcTemplate jdbcTemplate = createJdbcTemplate(source);
        if (jdbcTemplate == null) {
            source.setStatus("error");
            this.updateById(source);
            log.error("无法创建数据库连接: {}", source.getSourceName());
            return schemas;
        }

        try {
            List<String> databases = listDatabases(source, jdbcTemplate);
            for (String database : databases) {
                List<String> tables = listTables(source, jdbcTemplate, database);
                for (String table : tables) {
                    try {
                        SysMetadataSchema schema = extractSchema(source, jdbcTemplate, database, table);
                        if (schema != null) {
                            schemaMapper.insert(schema);
                            schemas.add(schema);
                            log.debug("已采集Schema: {}.{}", database, table);
                        }
                    } catch (Exception e) {
                        log.warn("采集Schema失败: {}.{}", database, table, e);
                    }
                }
            }

            source.setStatus("active");
            source.setLastScanAt(LocalDateTime.now());
            this.updateById(source);

            log.info("数据源扫描完成: {}, 采集 {} 个Schema", source.getSourceName(), schemas.size());

        } catch (Exception e) {
            source.setStatus("error");
            this.updateById(source);
            log.error("扫描数据源失败: {}", source.getSourceName(), e);
        }

        return schemas;
    }

    private JdbcTemplate createJdbcTemplate(SysMetadataSource source) {
        try {
            Map<String, Object> config = source.getConnectionConfig();
            String driverClass = getDriverClass(source.getSourceType());
            String url = buildJdbcUrl(source);
            String username = (String) config.get("username");
            String password = (String) config.get("password");

            DriverManagerDataSource dataSource = new DriverManagerDataSource();
            dataSource.setDriverClassName(driverClass);
            dataSource.setUrl(url);
            dataSource.setUsername(username);
            dataSource.setPassword(password);

            return new JdbcTemplate(dataSource);
        } catch (Exception e) {
            log.error("创建JdbcTemplate失败: {}", e.getMessage());
            return null;
        }
    }

    private String getDriverClass(String sourceType) {
        return switch (sourceType.toLowerCase()) {
            case "mysql" -> "com.mysql.cj.jdbc.Driver";
            case "postgresql" -> "org.postgresql.Driver";
            case "oracle" -> "oracle.jdbc.OracleDriver";
            default -> throw new IllegalArgumentException("不支持的数据源类型: " + sourceType);
        };
    }

    private String buildJdbcUrl(SysMetadataSource source) {
        Map<String, Object> config = source.getConnectionConfig();
        String host = (String) config.getOrDefault("host", "localhost");
        Integer port = (Integer) config.getOrDefault("port", getDefaultPort(source.getSourceType()));
        String database = (String) config.get("database");

        return switch (source.getSourceType().toLowerCase()) {
            case "mysql" -> String.format("jdbc:mysql://%s:%d/%s?useSSL=false&serverTimezone=Asia/Shanghai",
                    host, port, database != null ? database : "");
            case "postgresql" -> String.format("jdbc:postgresql://%s:%d/%s", host, port, database != null ? database : "");
            case "oracle" -> String.format("jdbc:oracle:thin:@%s:%d:%s", host, port, database != null ? database : "ORCL");
            default -> throw new IllegalArgumentException("不支持的数据源类型: " + source.getSourceType());
        };
    }

    private int getDefaultPort(String sourceType) {
        return switch (sourceType.toLowerCase()) {
            case "mysql" -> 3306;
            case "postgresql" -> 5432;
            case "oracle" -> 1521;
            default -> 3306;
        };
    }

    private List<String> listDatabases(SysMetadataSource source, JdbcTemplate jdbcTemplate) {
        List<String> databases = new ArrayList<>();
        try {
            String sql = switch (source.getSourceType().toLowerCase()) {
                case "mysql" -> "SHOW DATABASES";
                case "postgresql" -> "SELECT datname FROM pg_database WHERE datistemplate = false";
                case "oracle" -> "SELECT DISTINCT owner FROM all_tables";
                default -> "SELECT SCHEMA_NAME FROM INFORMATION_SCHEMA.SCHEMATA";
            };

            List<String> excluded = List.of("information_schema", "mysql", "performance_schema", "sys", "pg_catalog");

            jdbcTemplate.query(sql, rs -> {
                String dbName = rs.getString(1);
                if (!excluded.contains(dbName.toLowerCase())) {
                    databases.add(dbName);
                }
            });
        } catch (Exception e) {
            log.warn("获取数据库列表失败", e);
        }
        return databases;
    }

    private List<String> listTables(SysMetadataSource source, JdbcTemplate jdbcTemplate, String database) {
        List<String> tables = new ArrayList<>();
        try {
            String sql = switch (source.getSourceType().toLowerCase()) {
                case "mysql" -> String.format("SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = '%s' AND TABLE_TYPE = 'BASE TABLE'", database);
                case "postgresql" -> String.format("SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' AND table_catalog = '%s'", database);
                case "oracle" -> String.format("SELECT table_name FROM all_tables WHERE owner = '%s'", database.toUpperCase());
                default -> String.format("SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = '%s'", database);
            };

            jdbcTemplate.query(sql, rs -> {
                tables.add(rs.getString(1));
            });
        } catch (Exception e) {
            log.warn("获取表列表失败: {}", database, e);
        }
        return tables;
    }

    private SysMetadataSchema extractSchema(SysMetadataSource source, JdbcTemplate jdbcTemplate,
                                            String database, String table) throws Exception {
        SysMetadataSchema schema = new SysMetadataSchema();
        schema.setSchemaId("schema_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        schema.setSourceId(source.getSourceId());
        schema.setDatabaseName(database);
        schema.setTableName(table);
        schema.setCollectedAt(LocalDateTime.now());

        schema.setColumns(extractColumns(source, jdbcTemplate, database, table));
        schema.setStatistics(extractStatistics(source, jdbcTemplate, database, table));
        schema.setSampleData(extractSampleData(jdbcTemplate, database, table));

        Map<String, Object> stats = schema.getStatistics();
        if (stats != null) {
            schema.setRowCount(((Number) stats.getOrDefault("rowCount", 0L)).longValue());
            schema.setDataSize(((Number) stats.getOrDefault("dataSize", 0L)).longValue());
        }

        return schema;
    }

    private List<SysMetadataSchema.ColumnInfo> extractColumns(SysMetadataSource source,
                                                              JdbcTemplate jdbcTemplate,
                                                              String database, String table) {
        List<SysMetadataSchema.ColumnInfo> columns = new ArrayList<>();

        try (Connection conn = Objects.requireNonNull(jdbcTemplate.getDataSource()).getConnection();
             ResultSet rs = conn.getMetaData().getColumns(database, null, table, null)) {

            Set<String> primaryKeys = getPrimaryKeys(conn, database, table);

            while (rs.next()) {
                SysMetadataSchema.ColumnInfo col = new SysMetadataSchema.ColumnInfo();
                col.setName(rs.getString("COLUMN_NAME"));
                col.setType(rs.getString("TYPE_NAME"));
                col.setLength(rs.getInt("COLUMN_SIZE"));
                col.setPrecision(rs.getInt("COLUMN_SIZE"));
                col.setScale(rs.getInt("DECIMAL_DIGITS"));
                col.setNullable("YES".equalsIgnoreCase(rs.getString("IS_NULLABLE")));
                col.setDefaultValue(rs.getString("COLUMN_DEF"));
                col.setComment(rs.getString("REMARKS"));
                col.setPrimaryKey(primaryKeys.contains(col.getName()));
                col.setAutoIncrement("YES".equalsIgnoreCase(rs.getString("IS_AUTOINCREMENT")));
                columns.add(col);
            }
        } catch (Exception e) {
            log.warn("获取列信息失败: {}.{}", database, table, e);
        }

        return columns;
    }

    private Set<String> getPrimaryKeys(Connection conn, String database, String table) throws SQLException {
        Set<String> primaryKeys = new HashSet<>();
        try (ResultSet rs = conn.getMetaData().getPrimaryKeys(database, null, table)) {
            while (rs.next()) {
                primaryKeys.add(rs.getString("COLUMN_NAME"));
            }
        }
        return primaryKeys;
    }

    private Map<String, Object> extractStatistics(SysMetadataSource source,
                                                  JdbcTemplate jdbcTemplate,
                                                  String database, String table) {
        Map<String, Object> stats = new HashMap<>();
        try {
            String countSql = String.format("SELECT COUNT(*) FROM `%s`.`%s`", database, table);
            Long rowCount = jdbcTemplate.queryForObject(countSql, Long.class);
            stats.put("rowCount", rowCount != null ? rowCount : 0L);

            if ("mysql".equalsIgnoreCase(source.getSourceType())) {
                String sizeSql = String.format(
                        "SELECT (data_length + index_length) AS size FROM information_schema.tables " +
                                "WHERE table_schema = '%s' AND table_name = '%s'", database, table);
                Long dataSize = jdbcTemplate.queryForObject(sizeSql, Long.class);
                stats.put("dataSize", dataSize != null ? dataSize : 0L);
            }

            stats.put("collectedAt", LocalDateTime.now().toString());
        } catch (Exception e) {
            log.warn("获取统计信息失败: {}.{}", database, table, e);
        }
        return stats;
    }

    private List<Map<String, Object>> extractSampleData(JdbcTemplate jdbcTemplate,
                                                        String database, String table) {
        List<Map<String, Object>> sampleData = new ArrayList<>();
        try {
            String sampleSql = String.format("SELECT * FROM `%s`.`%s` LIMIT 10", database, table);
            sampleData = jdbcTemplate.queryForList(sampleSql);
        } catch (Exception e) {
            log.warn("获取样例数据失败: {}.{}", database, table, e);
        }
        return sampleData;
    }

    public List<SysMetadataSource> getAllSources() {
        return this.list();
    }

    public List<SysMetadataSchema> getSchemas(String sourceId, String database, String table) {
        LambdaQueryWrapper<SysMetadataSchema> wrapper = new LambdaQueryWrapper<>();
        if (sourceId != null && !sourceId.isEmpty()) {
            wrapper.eq(SysMetadataSchema::getSourceId, sourceId);
        }
        if (database != null && !database.isEmpty()) {
            wrapper.eq(SysMetadataSchema::getDatabaseName, database);
        }
        if (table != null && !table.isEmpty()) {
            wrapper.like(SysMetadataSchema::getTableName, table);
        }
        wrapper.orderByDesc(SysMetadataSchema::getCollectedAt);
        return schemaMapper.selectList(wrapper);
    }

    public SysMetadataSchema getSchemaById(String schemaId) {
        return schemaMapper.selectById(schemaId);
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean deleteSource(String sourceId) {
        schemaMapper.delete(new LambdaQueryWrapper<SysMetadataSchema>()
                .eq(SysMetadataSchema::getSourceId, sourceId));
        return this.removeById(sourceId);
    }

    @Transactional(rollbackFor = Exception.class)
    public SysMetadataSource updateSourceStatus(String sourceId, String status) {
        SysMetadataSource source = this.getById(sourceId);
        if (source == null) {
            throw new IllegalArgumentException("数据源不存在: " + sourceId);
        }
        source.setStatus(status);
        this.updateById(source);
        return source;
    }
}
