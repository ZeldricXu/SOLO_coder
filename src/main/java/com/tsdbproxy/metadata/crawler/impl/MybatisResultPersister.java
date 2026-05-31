package com.tsdbproxy.metadata.crawler.impl;

import com.tsdbproxy.common.entity.ColumnSchema;
import com.tsdbproxy.common.entity.TableSchema;
import com.tsdbproxy.common.mapper.ColumnSchemaMapper;
import com.tsdbproxy.common.mapper.TableSchemaMapper;
import com.tsdbproxy.metadata.crawler.model.CrawlResult;
import com.tsdbproxy.metadata.crawler.model.CrawlTask;
import com.tsdbproxy.metadata.crawler.spi.ResultPersister;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
@RequiredArgsConstructor
public class MybatisResultPersister implements ResultPersister {

    private final TableSchemaMapper tableSchemaMapper;
    private final ColumnSchemaMapper columnSchemaMapper;

    @Override
    public void persist(CrawlTask task, CrawlResult result) {
        if (!"success".equals(result.getStatus())) {
            return;
        }

        TableSchema tableSchema = new TableSchema();
        tableSchema.setDatasourceId(task.getDatasourceId());
        tableSchema.setSchemaName(result.getSchemaName());
        tableSchema.setTableName(result.getTableName());
        tableSchema.setTableComment(result.getTableComment());
        tableSchema.setRowCount(result.getRowCount());
        tableSchema.setSizeBytes(result.getSizeBytes());
        tableSchema.setSampleData(result.getSampleData());
        tableSchema.setCrawlStatus("success");
        tableSchema.setLastCrawlTime(LocalDateTime.now());
        tableSchemaMapper.insert(tableSchema);

        if (result.getColumns() != null) {
            for (CrawlResult.ColumnInfo col : result.getColumns()) {
                ColumnSchema columnSchema = new ColumnSchema();
                columnSchema.setTableId(tableSchema.getId());
                columnSchema.setColumnName(col.getColumnName());
                columnSchema.setColumnType(col.getColumnType());
                columnSchema.setColumnComment(col.getColumnComment());
                columnSchema.setIsNullable(col.getIsNullable());
                columnSchema.setIsPrimaryKey(col.getIsPrimaryKey());
                columnSchema.setOrdinalPosition(col.getOrdinalPosition());
                columnSchema.setMinValue(col.getMinValue());
                columnSchema.setMaxValue(col.getMaxValue());
                columnSchema.setDistinctCount(col.getDistinctCount());
                columnSchema.setNullCount(col.getNullCount());
                columnSchemaMapper.insert(columnSchema);
            }
        }
    }
}
