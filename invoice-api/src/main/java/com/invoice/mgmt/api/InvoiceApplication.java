package com.invoice.mgmt.api;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
@EnableConfigurationProperties
@ComponentScan(basePackages = {"com.invoice.mgmt"})
@MapperScan(basePackages = {"com.invoice.mgmt.common.mapper", "com.invoice.mgmt.type.mapper",
        "com.invoice.mgmt.number.mapper", "com.invoice.mgmt.status.mapper",
        "com.invoice.mgmt.history.mapper", "com.invoice.mgmt.archive.mapper",
        "com.invoice.mgmt.statistics.mapper", "com.invoice.mgmt.verify.mapper",
        "com.invoice.mgmt.reimburse.mapper"})
public class InvoiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(InvoiceApplication.class, args);
    }
}
