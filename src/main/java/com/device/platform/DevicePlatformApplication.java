package com.device.platform;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.device.platform.mapper")
public class DevicePlatformApplication {

    public static void main(String[] args) {
        SpringApplication.run(DevicePlatformApplication.class, args);
    }
}
