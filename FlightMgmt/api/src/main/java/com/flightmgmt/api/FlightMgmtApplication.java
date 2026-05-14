package com.flightmgmt.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

@SpringBootApplication
@ComponentScan(basePackages = "com.flightmgmt")
public class FlightMgmtApplication {
    public static void main(String[] args) {
        SpringApplication.run(FlightMgmtApplication.class, args);
        System.out.println("========================================");
        System.out.println("  FlightMgmt 航班信息管理服务已启动");
        System.out.println("========================================");
        System.out.println("  可用API接口:");
        System.out.println("  - GET  /api/v1/flights/search");
        System.out.println("         ?departure=北京&destination=上海&date=2026-05-11");
        System.out.println("  - POST /api/v1/bookings/create");
        System.out.println("  - GET  /api/v1/flights/status?flightNumber=CA1234");
        System.out.println("  - POST /api/v1/flights");
        System.out.println("  - GET  /api/v1/flights");
        System.out.println("========================================");
    }
}
