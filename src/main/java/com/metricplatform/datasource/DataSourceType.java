package com.metricplatform.datasource;

public class DataSourceType {
    public static final String MASTER = "master";
    public static final String SLAVE = "slave";

    private static final ThreadLocal<String> contextHolder = new ThreadLocal<>();

    public static void setDataSourceType(String type) {
        contextHolder.set(type);
    }

    public static String getDataSourceType() {
        return contextHolder.get() != null ? contextHolder.get() : MASTER;
    }

    public static void clearDataSourceType() {
        contextHolder.remove();
    }
}
