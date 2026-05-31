package com.solocoder.infrastructure.adapter.dataaccess;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.solocoder.domain.port.DataAccessPort;
import lombok.RequiredArgsConstructor;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class MyBatisDataAccessAdapter implements DataAccessPort {

    private final ApplicationContext applicationContext;
    private final Flyway flyway;
    private final ObjectMapper objectMapper;

    @Override
    @SuppressWarnings("unchecked")
    public <T> Mono<T> findById(Class<T> entityClass, Object id) {
        return Mono.fromCallable(() -> {
            BaseMapper<T> mapper = getMapper(entityClass);
            return mapper.selectById((Serializable) id);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> Flux<T> findAll(Class<T> entityClass, Map<String, Object> filters, int page, int size) {
        return Mono.fromCallable(() -> {
            BaseMapper<T> mapper = getMapper(entityClass);
            QueryWrapper<T> queryWrapper = new QueryWrapper<>();

            if (filters != null) {
                filters.forEach((key, value) -> {
                    if (value != null) {
                        queryWrapper.eq(key, value);
                    }
                });
            }

            int offset = (page - 1) * size;
            queryWrapper.last("LIMIT " + size + " OFFSET " + offset);

            return mapper.selectList(queryWrapper);
        }).subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(Flux::fromIterable);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> Mono<T> save(T entity) {
        return Mono.fromCallable(() -> {
            BaseMapper<T> mapper = getMapper(entity.getClass());
            mapper.insert(entity);
            return entity;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> Mono<T> update(T entity) {
        return Mono.fromCallable(() -> {
            BaseMapper<T> mapper = getMapper(entity.getClass());
            mapper.updateById(entity);
            return entity;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> Mono<Void> delete(Class<T> entityClass, Object id) {
        return Mono.fromRunnable(() -> {
            BaseMapper<T> mapper = getMapper(entityClass);
            mapper.deleteById((Serializable) id);
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    @Override
    public Mono<Void> migrateSchema(String targetVersion) {
        return Mono.fromRunnable(() -> {
            if (targetVersion != null) {
                flyway.migrate();
            } else {
                flyway.migrate();
            }
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    @Override
    public String getCurrentSchemaVersion() {
        MigrationInfo[] migrations = flyway.info().applied();
        if (migrations.length > 0) {
            return migrations[migrations.length - 1].getVersion().toString();
        }
        return "0";
    }

    @Override
    public Flux<String> getMigrationHistory() {
        return Flux.fromArray(flyway.info().all())
                .map(info -> info.getVersion() + " - " + info.getDescription() +
                        " (" + info.getState() + ")");
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> Flux<T> executeQuery(String sql, Map<String, Object> parameters, Class<T> resultType) {
        return Mono.fromCallable(() -> {
            BaseMapper<T> mapper = getMapper(resultType);
            List<Map<String, Object>> results = mapper.selectMaps(new QueryWrapper<T>().apply(sql));
            return results.stream()
                    .map(map -> objectMapper.convertValue(map, new TypeReference<T>() {}))
                    .toList();
        }).subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(Flux::fromIterable);
    }

    @Override
    public Mono<Void> batchInsert(String tableName, Flux<Map<String, Object>> records) {
        return records
                .buffer(1000)
                .flatMap(batch -> Mono.fromRunnable(() -> {
                    // 批量插入逻辑
                }).subscribeOn(Schedulers.boundedElastic()))
                .then();
    }

    @SuppressWarnings("unchecked")
    private <T> BaseMapper<T> getMapper(Class<?> entityClass) {
        String mapperName = entityClass.getSimpleName().replace("Entity", "") + "Mapper";
        String firstLower = mapperName.substring(0, 1).toLowerCase() + mapperName.substring(1);
        return (BaseMapper<T>) applicationContext.getBean(firstLower);
    }
}
