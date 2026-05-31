package com.web3platform.addressmanagement;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

@SpringBootApplication
@ComponentScan(basePackages = {"com.web3platform.addressmanagement", "com.web3platform.persistence"})
@MapperScan(basePackages = "com.web3platform.persistence.mapper")
public class AddressManagementApplication {

    public static void main(String[] args) {
        SpringApplication.run(AddressManagementApplication.class, args);
    }
}
