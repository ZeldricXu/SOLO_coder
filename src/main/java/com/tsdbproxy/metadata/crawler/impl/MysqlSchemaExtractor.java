package com.tsdbproxy.metadata.crawler.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.tsdbproxy.metadata.crawler.model.CrawlResult;
import com.tsdbproxy.metadata.crawler.model.CrawlTask;
import com.tsdbproxy.metadata.crawler.spi.SchemaExtractor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class MysqlSchemaExtractor implements SchemaExtractor {

    @Override
    public CrawlResult extract(Connection conn, CrawlTask task) {
        CrawlResult.CrawlResultBuilder builder = CrawlResult.builder()
                .datasourceId(task.getDatasourceId())
                .schemaName(task.getSchemaName())
                .tableName(task.getTableName())
                .crawlTime(LocalDateTime.now());

        try {
            String schema = StrUtil.isBlank(task.getSchemaName()) ? conn.getCatalog() : task.getSchemaName();
            extractTableInfo(conn, schema, task, builder);
            extractColumnInfo(conn, schema, task, builder);
            builder.status("success");
        } catch (Exception e) {
            log.error("提取Schema失败", e);
            builder.status("failed").errorMessage(e.getMessage());
        }

        return builder.build();
    }

    private void extractTableInfo(Connection conn, String schema, CrawlTask task, CrawlResult.CrawlResultBuilder builder) throws SQLException {
        String sql = "SELECT TABLE_COMMENT, TABLE_ROWS, DATA_LENGTH FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, schema);
            ps.setString(2, task.getTableName());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    builder.tableComment(rs.getString("TABLE_COMMENT"))
                            .rowCount(rs.getLong("TABLE_ROWS"))
                            .sizeBytes(rs.getLong("DATA_LENGTH"));
                }
            }
        }

        String sampleSql = String.format("SELECT * FROM `%s`.`%s` LIMIT ?", schema, task.getTableName());
        List<Map<String, Object>> sampleData = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sampleSql)) {
            ps.setInt(1, task.getSampleSize());
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData md = rs.getMetaData();
                int columnCount = md.getColumnCount();
                while (rs.next()) {
                    Map<String, Object> row = new HashMap<>();
                    for (int i = 1; i <= columnCount; i++) {
                        row.put(md.getColumnName(i), rs.getObject(i));
                    }
                    sampleData.add(row);
                }
            }
        }
        builder.sampleData(JSONUtil.toJsonStr(sampleData));
    }

    private void extractColumnInfo(Connection conn, String schema, CrawlTask task, CrawlResult.CrawlResultBuilder builder) throws SQLException {
        List<CrawlResult.ColumnInfo> columns = new ArrayList<>();

        String columnSql = "SELECT COLUMN_NAME, DATA_TYPE, COLUMN_COMMENT, IS_NULLABLE, COLUMN_KEY, ORDINAL_POSITION " +
                "FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? ORDER BY ORDINAL_POSITION";
        try (PreparedStatement ps = conn.prepareStatement(columnSql)) {
            ps.setString(1, schema);
            ps.setString(2, task.getTableName());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    CrawlResult.ColumnInfo column = CrawlResult.ColumnInfo.builder()
                            .columnName(rs.getString("COLUMN_NAME"))
                            .columnType(rs.getString("DATA_TYPE"))
                            .columnComment(rs.getString("COLUMN_COMMENT"))
                            .isNullable("YES".equals(rs.getString("IS_NULLABLE")) ? 1 : 0)
                            .isPrimaryKey("PRI".equals(rs.getString("COLUMN_KEY")) ? 1 : 0)
                            .ordinalPosition(rs.getInt("ORDINAL_POSITION"))
                            .build();
                    columns.add(column);
                }
            }
        }

        for (CrawlResult.ColumnInfo column : columns) {
            extractColumnStats(conn, schema, task.getTableName(), column);
        }

        builder.columns(columns);
    }

    private void extractColumnStats(Connection conn, String schema, String tableName, CrawlResult.ColumnInfo column) {
        try {
            String sql = String.format("SELECT MIN(`%s`) as min_val, MAX(`%s`) as max_val, " +
                            "COUNT(DISTINCT `%s`) as distinct_cnt, " +
                            "SUM(CASE WHEN `%s` IS NULL THEN 1 ELSE 0 END) as null_cnt FROM `%s`.`%s`",
                    column.getColumnName(), column.getColumnName(), column.getColumnName(),
                    column.getColumnName(), schema, tableName);
            try (PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    Object minVal = rs.getObject("min_val");
                    Object maxVal = rs.getObject("max_val");
                    column.setMinValue(minVal != null ? minVal.toString() : null);
                    column.setMaxValue(maxVal != null ? maxVal.toString() : null);
                    column.setDistinctCount(rs.getLong("distinct_cnt"));
                    column.setNullCount(rs.getLong("null_cnt"));
                }
            }
        } catch (Exception e) {
            log.warn("统计列信息失败: {}", column.getColumnName(), e);
        }
    }
}
