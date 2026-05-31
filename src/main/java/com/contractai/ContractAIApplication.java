package com.contractai;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableAsync
@EnableScheduling
@MapperScan("com.contractai.**.mapper")
public class ContractAIApplication {

    public static void main(String[] args) {
        SpringApplication.run(ContractAIApplication.class, args);
    }
}
