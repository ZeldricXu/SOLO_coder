package com.cdcsync.metadata.crawler;

import com.cdcsync.metadata.domain.DataSource;
import com.cdcsync.metadata.mapper.TableInfoMapper;
import lombok.extern.slf4j.Slf4j;

@Slf4j
class MysqlSchemaCrawler extends AbstractSchemaCrawler {

    MysqlSchemaCrawler(DataSource dataSource, TableInfoMapper tableInfoMapper) {
        super(dataSource, tableInfoMapper);
    }

    @Override
    protected String getDatabaseType() {
        return "MySQL";
    }
}
