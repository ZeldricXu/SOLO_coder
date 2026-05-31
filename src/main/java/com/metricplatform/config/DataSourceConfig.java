package com.metricplatform.config;

import com.metricplatform.datasource.RoutingDataSource;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Configuration
@EnableTransactionManagement
@ConditionalOnProperty(name = "datasource.routing.enabled", havingValue = "true", matchIfMissing = true)
public class DataSourceConfig {

    @Bean(name = "masterDataSource")
    @ConfigurationProperties(prefix = "spring.datasource.master")
    public DataSource masterDataSource() {
        HikariDataSource dataSource = DataSourceBuilder.create().type(HikariDataSource.class).build();
        log.info("主数据源初始化完成: {}", dataSource.getJdbcUrl());
        return dataSource;
    }

    @Bean(name = "slaveDataSource")
    @ConfigurationProperties(prefix = "spring.datasource.slave")
    @ConditionalOnProperty(name = "spring.datasource.slave.jdbc-url")
    public DataSource slaveDataSource() {
        HikariDataSource dataSource = DataSourceBuilder.create().type(HikariDataSource.class).build();
        log.info("从数据源初始化完成: {}", dataSource.getJdbcUrl());
        return dataSource;
    }

    @Bean
    @Primary
    public DataSource routingDataSource(@Qualifier("masterDataSource") DataSource masterDataSource,
                                        @Qualifier("slaveDataSource") Optional<DataSource> slaveDataSourceOpt) {
        Map<Object, Object> targetDataSources = new HashMap<>();
        targetDataSources.put("master", masterDataSource);

        DataSource slaveDataSource = slaveDataSourceOpt.orElse(masterDataSource);
        targetDataSources.put("slave", slaveDataSource);

        if (slaveDataSource == masterDataSource) {
            log.warn("未配置从数据源，读写分离将使用主数据源作为从库，读写分离功能降级");
        }

        RoutingDataSource routingDataSource = new RoutingDataSource(masterDataSource, targetDataSources);
        log.info("读写分离数据源路由初始化完成，主从切换已启用");
        return routingDataSource;
    }

    @Bean
    @ConditionalOnMissingBean
    public PlatformTransactionManager transactionManager(DataSource routingDataSource) {
        DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(routingDataSource);
        log.info("事务管理器初始化完成，支持读写分离事务");
        return transactionManager;
    }
}
