package com.cardgame.common.config;

import lombok.Data;

@Data
public class MysqlConfig {
    private String url = "jdbc:mysql://127.0.0.1:3306/cardgame?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai";
    private String username = "root";
    private String password = "root";
    private String driverClassName = "com.mysql.cj.jdbc.Driver";
    private int maximumPoolSize = 20;
    private int minimumIdle = 5;
    private long connectionTimeout = 30000;
    private long idleTimeout = 600000;
    private long maxLifetime = 1800000;
}
