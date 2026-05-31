package com.cdcsync.test.builder;

import com.cdcsync.streamquery.domain.StreamQuery;

import java.time.LocalDateTime;
import java.util.UUID;

public class StreamQueryBuilder {

    private final StreamQuery query;

    private StreamQueryBuilder() {
        this.query = new StreamQuery();
    }

    public static StreamQueryBuilder aStreamQuery() {
        return new StreamQueryBuilder();
    }

    public StreamQueryBuilder withDefaults() {
        return withId("sq_" + UUID.randomUUID().toString().substring(0, 8))
                .withName("Test Query")
                .withSqlText("SELECT id, name FROM users WHERE status = 'active'")
                .withStatus("DRAFT")
                .withExecutionCount(0)
                .withCreatedAt(LocalDateTime.now())
                .withUpdatedAt(LocalDateTime.now())
                .withDeleted(0);
    }

    public StreamQueryBuilder withId(String id) {
        query.setId(id);
        return this;
    }

    public StreamQueryBuilder withName(String name) {
        query.setName(name);
        return this;
    }

    public StreamQueryBuilder withSqlText(String sqlText) {
        query.setSqlText(sqlText);
        return this;
    }

    public StreamQueryBuilder withParsedPlanJson(String parsedPlanJson) {
        query.setParsedPlanJson(parsedPlanJson);
        return this;
    }

    public StreamQueryBuilder withOptimizedPlanJson(String optimizedPlanJson) {
        query.setOptimizedPlanJson(optimizedPlanJson);
        return this;
    }

    public StreamQueryBuilder withPhysicalPlanJson(String physicalPlanJson) {
        query.setPhysicalPlanJson(physicalPlanJson);
        return this;
    }

    public StreamQueryBuilder withStatus(String status) {
        query.setStatus(status);
        return this;
    }

    public StreamQueryBuilder withExecutionConfig(String executionConfig) {
        query.setExecutionConfig(executionConfig);
        return this;
    }

    public StreamQueryBuilder withLastExecutedAt(LocalDateTime lastExecutedAt) {
        query.setLastExecutedAt(lastExecutedAt);
        return this;
    }

    public StreamQueryBuilder withExecutionCount(Integer executionCount) {
        query.setExecutionCount(executionCount);
        return this;
    }

    public StreamQueryBuilder withCreatedAt(LocalDateTime createdAt) {
        query.setCreatedAt(createdAt);
        return this;
    }

    public StreamQueryBuilder withUpdatedAt(LocalDateTime updatedAt) {
        query.setUpdatedAt(updatedAt);
        return this;
    }

    public StreamQueryBuilder withDeleted(Integer deleted) {
        query.setDeleted(deleted);
        return this;
    }

    public StreamQuery build() {
        return query;
    }
}
