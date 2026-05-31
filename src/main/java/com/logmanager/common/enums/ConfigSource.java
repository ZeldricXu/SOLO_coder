package com.logmanager.common.enums;

import lombok.Getter;

@Getter
public enum ConfigSource {
    DATABASE("database", "数据库"),
    REDIS("redis", "Redis"),
    NACOS("nacos", "Nacos"),
    APOLLO("apollo", "Apollo"),
    ENVIRONMENT("environment", "环境变量"),
    FILE("file", "配置文件");

    private final String code;
    private final String description;

    ConfigSource(String code, String description) {
        this.code = code;
        this.description = description;
    }
}
