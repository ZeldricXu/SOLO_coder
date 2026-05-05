package com.iotconnect;

import com.iotconnect.config.BatchConfigProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(BatchConfigProperties.class)
public class IoTConnectApplication {

    public static void main(String[] args) {
        SpringApplication.run(IoTConnectApplication.class, args);
    }
}
