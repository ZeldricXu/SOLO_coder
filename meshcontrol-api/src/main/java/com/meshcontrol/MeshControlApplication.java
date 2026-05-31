package com.meshcontrol;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "com.meshcontrol")
@MapperScan(basePackages = {
        "com.meshcontrol.eventstore.mapper",
        "com.meshcontrol.sidecar.mapper",
        "com.meshcontrol.dns.mapper",
        "com.meshcontrol.traffic.mapper",
        "com.meshcontrol.mtls.mapper",
        "com.meshcontrol.fault.mapper",
        "com.meshcontrol.audit.mapper",
        "com.meshcontrol.image.mapper"
})
@EnableScheduling
@EnableAsync
public class MeshControlApplication {

    public static void main(String[] args) {
        SpringApplication.run(MeshControlApplication.class, args);
    }
}
