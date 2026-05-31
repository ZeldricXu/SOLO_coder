package com.nftindexer;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableAsync
@EnableScheduling
@MapperScan("com.nftindexer.mapper")
public class NFTIndexerApplication {

    public static void main(String[] args) {
        SpringApplication.run(NFTIndexerApplication.class, args);
    }
}
