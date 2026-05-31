package com.monitoring.dal.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

@Configuration
@ComponentScan(basePackages = "com.monitoring.dal")
@MapperScan("com.monitoring.persistence.mapper")
public class DalAutoConfiguration {
}
