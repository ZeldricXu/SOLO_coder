package com.contraudit;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@MapperScan("com.contraudit.**.mapper")
@EnableAsync
@EnableScheduling
public class ContractAuditApplication {

    public static void main(String[] args) {
        SpringApplication.run(ContractAuditApplication.class, args);
        System.out.println("=============================================");
        System.out.println("  Contract Audit Platform Started Successfully!");
        System.out.println("  API Base: http://localhost:8080/api/v1");
        System.out.println("  Actuator: http://localhost:8080/actuator");
        System.out.println("=============================================");
    }
}
