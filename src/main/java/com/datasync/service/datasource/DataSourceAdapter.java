package com.datasync.service.datasource;

import com.datasync.model.DataSourceConfig;

import java.util.List;
import java.util.Map;

public interface DataSourceAdapter {

    void connect(DataSourceConfig config) throws Exception;

    void disconnect();

    boolean isConnected();

    List<Map<String, Object>> readData(String tableName, String filterRule, String dataKeyField) throws Exception;

    Map<String, Object> readSingle(String tableName, String dataKeyField, String dataKey) throws Exception;

    void writeData(String tableName, String dataKeyField, Map<String, Object> data) throws Exception;

    void batchWrite(String tableName, String dataKeyField, List<Map<String, Object>> dataList) throws Exception;

    void updateData(String tableName, String dataKeyField, String dataKey, Map<String, Object> data) throws Exception;

    void deleteData(String tableName, String dataKeyField, String dataKey) throws Exception;

    boolean exists(String tableName, String dataKeyField, String dataKey) throws Exception;

    String getType();
}
