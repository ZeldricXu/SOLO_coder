package com.datasync.service.datasource;

import com.datasync.common.Constants;
import com.datasync.model.DataSourceConfig;
import com.datasync.service.datasource.impl.JdbcDataSourceAdapter;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

@Component
public class DataSourceAdapterFactory {

    private final Map<String, DataSourceAdapter> adapterCache = new ConcurrentHashMap<>();

    public DataSourceAdapter getAdapter(DataSourceConfig config) throws Exception {
        String sourceId = config.getSourceId();

        DataSourceAdapter cached = adapterCache.get(sourceId);
        if (cached != null && cached.isConnected()) {
            return cached;
        }

        DataSourceAdapter adapter = createAdapter(config);
        adapter.connect(config);
        adapterCache.put(sourceId, adapter);

        return adapter;
    }

    private DataSourceAdapter createAdapter(DataSourceConfig config) {
        String type = config.getSourceType();

        if (Constants.DATA_SOURCE_TYPE_MYSQL.equals(type) ||
            Constants.DATA_SOURCE_TYPE_POSTGRESQL.equals(type) ||
            Constants.DATA_SOURCE_TYPE_ORACLE.equals(type)) {
            return new JdbcDataSourceAdapter();
        }

        throw new IllegalArgumentException("Unsupported data source type: " + type);
    }

    public void closeAdapter(String sourceId) {
        DataSourceAdapter adapter = adapterCache.remove(sourceId);
        if (adapter != null) {
            adapter.disconnect();
        }
    }

    public void closeAll() {
        for (Map.Entry<String, DataSourceAdapter> entry : adapterCache.entrySet()) {
            entry.getValue().disconnect();
        }
        adapterCache.clear();
    }
}
