package com.solocoder.platform.transaction;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

@SpringBootApplication
@ComponentScan(basePackages = {
        "com.solocoder.platform.transaction",
        "com.solocoder.platform.gas.estimator",
        "com.solocoder.platform.persistence"
})
@MapperScan("com.solocoder.platform.persistence.mapper")
public class TransactionBuilderApplication {
    public static void main(String[] args) {
        SpringApplication.run(TransactionBuilderApplication.class, args);
    }
}
