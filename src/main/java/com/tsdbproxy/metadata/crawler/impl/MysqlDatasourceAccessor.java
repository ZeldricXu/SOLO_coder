package com.tsdbproxy.metadata.crawler.impl;

import com.tsdbproxy.common.entity.Datasource;
import com.tsdbproxy.common.mapper.DatasourceMapper;
import com.tsdbproxy.metadata.crawler.model.CrawlTask;
import com.tsdbproxy.metadata.crawler.spi.DatasourceAccessor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.sql.Connection;
import java.sql.DriverManager;

@Slf4j
@Component
@RequiredArgsConstructor
public class MysqlDatasourceAccessor implements DatasourceAccessor {

    private final DatasourceMapper datasourceMapper;

    @Override
    public Mono<Connection> getConnection(CrawlTask task) {
        return Mono.fromCallable(() -> {
            Datasource ds = datasourceMapper.selectById(task.getDatasourceId());
            if (ds == null) {
                throw new IllegalArgumentException("数据源不存在: " + task.getDatasourceId());
            }

            String url = String.format(
                    "jdbc:mysql://%s:%d/%s?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai",
                    ds.getHost(), ds.getPort(), ds.getDatabase());

            return DriverManager.getConnection(url, ds.getUsername(), ds.getPassword());
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public String getType() {
        return "mysql";
    }
}
