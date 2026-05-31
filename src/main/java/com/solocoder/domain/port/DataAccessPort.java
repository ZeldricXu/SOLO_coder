package com.solocoder.domain.port;

import com.solocoder.domain.model.CoreEntity;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;

public interface DataAccessPort {

    <T> Mono<T> findById(Class<T> entityClass, Object id);

    <T> Flux<T> findAll(Class<T> entityClass, Map<String, Object> filters, int page, int size);

    <T> Mono<T> save(T entity);

    <T> Mono<T> update(T entity);

    <T> Mono<Void> delete(Class<T> entityClass, Object id);

    Mono<Void> migrateSchema(String targetVersion);

    String getCurrentSchemaVersion();

    Flux<String> getMigrationHistory();

    <T> Flux<T> executeQuery(String sql, Map<String, Object> parameters, Class<T> resultType);

    Mono<Void> batchInsert(String tableName, Flux<Map<String, Object>> records);
}
