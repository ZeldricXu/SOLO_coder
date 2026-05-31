package com.cdcsync.test.builder;

import com.cdcsync.metadata.domain.DataSource;

import java.time.LocalDateTime;
import java.util.UUID;

public class DataSourceBuilder {

    private final DataSource dataSource;

    private DataSourceBuilder() {
        this.dataSource = new DataSource();
    }

    public static DataSourceBuilder aDataSource() {
        return new DataSourceBuilder();
    }

    public DataSourceBuilder withDefaults() {
        return withId("ds_" + UUID.randomUUID().toString().substring(0, 8))
                .withName("Test MySQL DataSource")
                .withType("mysql")
                .withHost("localhost")
                .withPort(3306)
                .withDatabaseName("test_db")
                .withUsername("root")
                .withPassword("password")
                .withStatus("ACTIVE")
                .withCreatedAt(LocalDateTime.now())
                .withUpdatedAt(LocalDateTime.now())
                .withDeleted(0);
    }

    public DataSourceBuilder withId(String id) {
        dataSource.setId(id);
        return this;
    }

    public DataSourceBuilder withName(String name) {
        dataSource.setName(name);
        return this;
    }

    public DataSourceBuilder withType(String type) {
        dataSource.setType(type);
        return this;
    }

    public DataSourceBuilder withHost(String host) {
        dataSource.setHost(host);
        return this;
    }

    public DataSourceBuilder withPort(Integer port) {
        dataSource.setPort(port);
        return this;
    }

    public DataSourceBuilder withDatabaseName(String databaseName) {
        dataSource.setDatabaseName(databaseName);
        return this;
    }

    public DataSourceBuilder withUsername(String username) {
        dataSource.setUsername(username);
        return this;
    }

    public DataSourceBuilder withPassword(String password) {
        dataSource.setPassword(password);
        return this;
    }

    public DataSourceBuilder withConfigJson(String configJson) {
        dataSource.setConfigJson(configJson);
        return this;
    }

    public DataSourceBuilder withStatus(String status) {
        dataSource.setStatus(status);
        return this;
    }

    public DataSourceBuilder withCreatedAt(LocalDateTime createdAt) {
        dataSource.setCreatedAt(createdAt);
        return this;
    }

    public DataSourceBuilder withUpdatedAt(LocalDateTime updatedAt) {
        dataSource.setUpdatedAt(updatedAt);
        return this;
    }

    public DataSourceBuilder withDeleted(Integer deleted) {
        dataSource.setDeleted(deleted);
        return this;
    }

    public DataSource build() {
        return dataSource;
    }
}
