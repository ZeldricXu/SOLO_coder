package com.contractmgmt;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ContractMgmtApplication {

    public static void main(String[] args) {
        SpringApplication.run(ContractMgmtApplication.class, args);
    }
}
