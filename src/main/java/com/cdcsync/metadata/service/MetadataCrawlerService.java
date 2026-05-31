package com.cdcsync.metadata.service;

import com.cdcsync.common.service.BaseService;
import com.cdcsync.metadata.domain.SchemaInfo;
import com.cdcsync.metadata.domain.TableInfo;

import java.util.Map;

public interface MetadataCrawlerService extends BaseService<SchemaInfo, String> {

    SchemaInfo crawlFullSchema(String dataSourceId);

    TableInfo crawlTable(String dataSourceId, String tableName);

    Map<String, Object> analyzeTable(String dataSourceId, String tableName);
}
