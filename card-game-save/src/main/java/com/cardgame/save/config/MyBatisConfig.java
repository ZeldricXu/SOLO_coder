package com.cardgame.save.config;

import com.cardgame.common.config.MysqlConfig;
import com.cardgame.save.handler.JsonTypeHandler;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.util.Properties;

@Configuration
@EnableTransactionManagement
@MapperScan("com.cardgame.save.mapper")
public class MyBatisConfig {

    @Autowired
    private MysqlConfig mysqlConfig;

    @Bean
    public DataSource dataSource() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(mysqlConfig.getUrl());
        config.setUsername(mysqlConfig.getUsername());
        config.setPassword(mysqlConfig.getPassword());
        config.setDriverClassName(mysqlConfig.getDriverClassName());
        config.setMaximumPoolSize(mysqlConfig.getMaximumPoolSize());
        config.setMinimumIdle(mysqlConfig.getMinimumIdle());
        config.setConnectionTimeout(mysqlConfig.getConnectionTimeout());
        config.setIdleTimeout(mysqlConfig.getIdleTimeout());
        config.setMaxLifetime(mysqlConfig.getMaxLifetime());
        return new HikariDataSource(config);
    }

    @Bean
    public SqlSessionFactory sqlSessionFactory() throws Exception {
        SqlSessionFactoryBean factoryBean = new SqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource());

        org.apache.ibatis.session.Configuration configuration = new org.apache.ibatis.session.Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.getTypeHandlerRegistry().register(java.util.List.class, new JsonTypeHandler<>());
        configuration.getTypeHandlerRegistry().register(java.util.Map.class, new JsonTypeHandler<>());
        factoryBean.setConfiguration(configuration);

        return factoryBean.getObject();
    }

    @Bean
    public PlatformTransactionManager transactionManager() {
        return new DataSourceTransactionManager(dataSource());
    }
}
