package com.cdcsync.common.spi;

import com.cdcsync.metadata.domain.SchemaInfo;
import com.cdcsync.metadata.domain.TableInfo;

import java.util.List;
import java.util.Map;

public interface SchemaCrawler {

    SchemaInfo crawlSchema(String dataSourceId);

    List<TableInfo> listTables(String dataSourceId);

    TableInfo getTableInfo(String dataSourceId, String tableName);

    Map<String, Object> getTableStatistics(String dataSourceId, String tableName);

    List<Map<String, Object>> getSampleData(String dataSourceId, String tableName, int limit);
}
