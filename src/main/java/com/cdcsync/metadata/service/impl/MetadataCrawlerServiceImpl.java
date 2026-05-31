package com.cdcsync.metadata.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.cdcsync.common.exception.BusinessException;
import com.cdcsync.common.service.AbstractBaseService;
import com.cdcsync.common.spi.SchemaCrawler;
import com.cdcsync.common.util.ValidationUtils;
import com.cdcsync.metadata.crawler.MysqlSchemaCrawler;
import com.cdcsync.metadata.domain.DataSource;
import com.cdcsync.metadata.domain.SchemaInfo;
import com.cdcsync.metadata.domain.TableInfo;
import com.cdcsync.metadata.mapper.DataSourceMapper;
import com.cdcsync.metadata.mapper.SchemaInfoMapper;
import com.cdcsync.metadata.mapper.TableInfoMapper;
import com.cdcsync.metadata.service.MetadataCrawlerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class MetadataCrawlerServiceImpl extends AbstractBaseService<SchemaInfo, String, SchemaInfoMapper>
        implements MetadataCrawlerService, SchemaCrawler {

    private static final Set<String> SUPPORTED_DATABASE_TYPES = Set.of("mysql", "postgresql", "oracle");

    private final SchemaInfoMapper schemaInfoMapper;
    private final DataSourceMapper dataSourceMapper;
    private final TableInfoMapper tableInfoMapper;

    @Override
    protected void setId(SchemaInfo entity, String id) {
        entity.setId(id);
    }

    @Override
    protected String getId(SchemaInfo entity) {
        return entity.getId();
    }

    @Override
    @Transactional
    public SchemaInfo crawlFullSchema(String dataSourceId) {
        ValidationUtils.notBlank(dataSourceId, "dataSourceId");
        log.info("Starting full schema crawl for data source: {}", dataSourceId);

        DataSource dataSource = getDataSource(dataSourceId);
        SchemaCrawler crawler = createCrawler(dataSource);

        SchemaInfo schemaInfo = crawler.crawlSchema(dataSourceId);

        QueryWrapper<SchemaInfo> wrapper = new QueryWrapper<>();
        wrapper.eq("data_source_id", dataSourceId);
        SchemaInfo existing = schemaInfoMapper.selectOne(wrapper);

        if (existing != null) {
            schemaInfo.setId(existing.getId());
            schemaInfoMapper.updateById(schemaInfo);
        } else {
            schemaInfoMapper.insert(schemaInfo);
        }

        List<TableInfo> tables = crawler.listTables(dataSourceId);
        for (TableInfo table : tables) {
            try {
                TableInfo fullTableInfo = crawler.getTableInfo(dataSourceId, table.getTableName());
                saveOrUpdateTableInfo(fullTableInfo);
            } catch (Exception e) {
                log.error("Failed to crawl table {} for data source {}: {}",
                        table.getTableName(), dataSourceId, e.getMessage());
            }
        }

        log.info("Full schema crawl completed for data source: {}", dataSourceId);
        return schemaInfo;
    }

    @Override
    @Transactional
    public TableInfo crawlTable(String dataSourceId, String tableName) {
        ValidationUtils.notBlank(dataSourceId, "dataSourceId");
        ValidationUtils.notBlank(tableName, "tableName");

        log.info("Crawling table {} for data source: {}", tableName, dataSourceId);

        DataSource dataSource = getDataSource(dataSourceId);
        SchemaCrawler crawler = createCrawler(dataSource);

        TableInfo tableInfo = crawler.getTableInfo(dataSourceId, tableName);
        saveOrUpdateTableInfo(tableInfo);

        log.info("Table crawl completed for {}.{}", dataSourceId, tableName);
        return tableInfo;
    }

    @Override
    public Map<String, Object> analyzeTable(String dataSourceId, String tableName) {
        ValidationUtils.notBlank(dataSourceId, "dataSourceId");
        ValidationUtils.notBlank(tableName, "tableName");

        log.info("Analyzing table {} for data source: {}", tableName, dataSourceId);

        DataSource dataSource = getDataSource(dataSourceId);
        SchemaCrawler crawler = createCrawler(dataSource);

        return crawler.getTableStatistics(dataSourceId, tableName);
    }

    @Override
    public SchemaInfo crawlSchema(String dataSourceId) {
        ValidationUtils.notBlank(dataSourceId, "dataSourceId");
        DataSource dataSource = getDataSource(dataSourceId);
        SchemaCrawler crawler = createCrawler(dataSource);
        return crawler.crawlSchema(dataSourceId);
    }

    @Override
    public List<TableInfo> listTables(String dataSourceId) {
        ValidationUtils.notBlank(dataSourceId, "dataSourceId");
        DataSource dataSource = getDataSource(dataSourceId);
        SchemaCrawler crawler = createCrawler(dataSource);
        return crawler.listTables(dataSourceId);
    }

    @Override
    public TableInfo getTableInfo(String dataSourceId, String tableName) {
        ValidationUtils.notBlank(dataSourceId, "dataSourceId");
        ValidationUtils.notBlank(tableName, "tableName");
        DataSource dataSource = getDataSource(dataSourceId);
        SchemaCrawler crawler = createCrawler(dataSource);
        return crawler.getTableInfo(dataSourceId, tableName);
    }

    @Override
    public Map<String, Object> getTableStatistics(String dataSourceId, String tableName) {
        return analyzeTable(dataSourceId, tableName);
    }

    @Override
    public List<Map<String, Object>> getSampleData(String dataSourceId, String tableName, int limit) {
        ValidationUtils.notBlank(dataSourceId, "dataSourceId");
        ValidationUtils.notBlank(tableName, "tableName");
        DataSource dataSource = getDataSource(dataSourceId);
        SchemaCrawler crawler = createCrawler(dataSource);
        return crawler.getSampleData(dataSourceId, tableName, limit);
    }

    private DataSource getDataSource(String dataSourceId) {
        ValidationUtils.notBlank(dataSourceId, "dataSourceId");

        DataSource dataSource = dataSourceMapper.selectById(dataSourceId);
        if (dataSource == null) {
            throw new BusinessException("DataSource not found: " + dataSourceId);
        }

        validateDataSource(dataSource);
        return dataSource;
    }

    private void validateDataSource(DataSource dataSource) {
        ValidationUtils.notBlank(dataSource.getType(), "dataSource.type");
        ValidationUtils.notBlank(dataSource.getHost(), "dataSource.host");
        ValidationUtils.notNull(dataSource.getPort(), "dataSource.port");
        ValidationUtils.validPort(dataSource.getPort());
        ValidationUtils.notBlank(dataSource.getDatabaseName(), "dataSource.databaseName");

        String type = dataSource.getType().toLowerCase().trim();
        if (!SUPPORTED_DATABASE_TYPES.contains(type)) {
            throw new BusinessException(400,
                    "Unsupported database type: " + dataSource.getType() +
                            ". Supported types: " + SUPPORTED_DATABASE_TYPES);
        }

        if (!"ACTIVE".equalsIgnoreCase(dataSource.getStatus())) {
            throw new BusinessException(400,
                    "DataSource is not active, status: " + dataSource.getStatus());
        }
    }

    private SchemaCrawler createCrawler(DataSource dataSource) {
        ValidationUtils.notNull(dataSource, "dataSource");

        String type = dataSource.getType();
        if (type == null) {
            throw new BusinessException(400, "Database type is not specified");
        }

        String lowerType = type.toLowerCase().trim();
        return switch (lowerType) {
            case "mysql" -> new MysqlSchemaCrawler(dataSource, tableInfoMapper);
            default -> throw new BusinessException(400,
                    "Unsupported database type: " + type +
                            ". Supported types: " + SUPPORTED_DATABASE_TYPES);
        };
    }

    private void saveOrUpdateTableInfo(TableInfo tableInfo) {
        QueryWrapper<TableInfo> wrapper = new QueryWrapper<>();
        wrapper.eq("data_source_id", tableInfo.getDataSourceId())
                .eq("table_name", tableInfo.getTableName());
        TableInfo existing = tableInfoMapper.selectOne(wrapper);

        if (existing != null) {
            tableInfo.setId(existing.getId());
            tableInfoMapper.updateById(tableInfo);
        } else {
            tableInfoMapper.insert(tableInfo);
        }
    }
}
