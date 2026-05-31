package com.taskplatform.config;

public interface ConfigSource {

    String getName();

    String getValue(String namespace, String key);

    boolean isAvailable();

    int getPriority();
}
