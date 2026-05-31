package com.solo.config.module.config;

public interface ConfigSource {

    String getType();

    int getPriority();

    boolean isEnabled();

    default boolean isReadOnly() {
        return false;
    }

    default boolean isWriteOnly() {
        return false;
    }

    default boolean canRead() {
        return isEnabled() && !isWriteOnly();
    }

    default boolean canWrite() {
        return isEnabled() && !isReadOnly();
    }

    String getConfig(String namespace, String key);

    void setConfig(String namespace, String key, String value);

    void refresh();
}
