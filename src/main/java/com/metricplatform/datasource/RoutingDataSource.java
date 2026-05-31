package com.metricplatform.datasource;

import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

import javax.sql.DataSource;
import java.util.Map;

public class RoutingDataSource extends AbstractRoutingDataSource {

    public RoutingDataSource(DataSource defaultTargetDataSource, Map<Object, Object> targetDataSources) {
        super.setDefaultTargetDataSource(defaultTargetDataSource);
        super.setTargetDataSources(targetDataSources);
        super.afterPropertiesSet();
    }

    @Override
    protected Object determineCurrentLookupKey() {
        String dataSourceType = DataSourceType.getDataSourceType();
        logger.debug("当前使用数据源: " + dataSourceType);
        return dataSourceType;
    }
}
