package com.solocoder.platform.storage;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

@SpringBootApplication
@ComponentScan(basePackages = {
        "com.solocoder.platform.storage",
        "com.solocoder.platform.persistence"
})
@MapperScan("com.solocoder.platform.persistence.mapper")
public class StorageAdapterApplication {
    public static void main(String[] args) {
        SpringApplication.run(StorageAdapterApplication.class, args);
    }
}
