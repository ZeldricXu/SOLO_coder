package com.observability.common.enums;

import lombok.Getter;

@Getter
public enum ConfigSource {

    DATABASE("database", "数据库"),
    NACOS("nacos", "Nacos配置中心"),
    APOLLO("apollo", "Apollo配置中心"),
    CONSUL("consul", "Consul"),
    FILE("file", "本地文件"),
    ENV("env", "环境变量"),
    SYSTEM("system", "系统配置");

    private final String code;
    private final String desc;

    ConfigSource(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }
}
