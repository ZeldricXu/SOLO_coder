package com.cardgame.common.config;

import lombok.Data;

@Data
public class RedisConfig {
    private String host = "127.0.0.1";
    private int port = 6379;
    private String password;
    private int database = 0;
    private int timeout = 3000;
    private int maxIdle = 8;
    private int minIdle = 0;
    private int maxTotal = 8;
}
