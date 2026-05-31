package com.chainetl;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@MapperScan({"com.chainetl.common.mapper", "com.chainetl.modules.**.mapper"})
public class ChainETLApplication {

    public static void main(String[] args) {
        SpringApplication.run(ChainETLApplication.class, args);
    }
}
