package com.web3platform.chainindexer;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@EnableAsync
@SpringBootApplication(scanBasePackages = {
        "com.web3platform.chainindexer",
        "com.web3platform.chaininteraction",
        "com.web3platform.persistence"
})
@MapperScan("com.web3platform.persistence.mapper")
public class ChainIndexerApplication {

    public static void main(String[] args) {
        SpringApplication.run(ChainIndexerApplication.class, args);
    }
}
