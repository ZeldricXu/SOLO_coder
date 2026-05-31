package com.solocoder.platform.gas.estimator;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

@SpringBootApplication
@ComponentScan(basePackages = {
        "com.solocoder.platform.gas.estimator",
        "com.solocoder.platform.persistence"
})
@MapperScan("com.solocoder.platform.persistence.mapper")
public class GasEstimatorApplication {
    public static void main(String[] args) {
        SpringApplication.run(GasEstimatorApplication.class, args);
    }
}
