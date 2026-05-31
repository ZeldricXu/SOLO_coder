package com.solocoder.application.service;

import com.solocoder.domain.model.ApiResponse;
import com.solocoder.domain.port.DataAccessPort;
import com.solocoder.domain.port.StructuredLoggerPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;

@Service
@RequiredArgsConstructor
public class DataAccessService {

    private final DataAccessPort dataAccessPort;
    private final StructuredLoggerPort logger;

    public <T> Mono<ApiResponse<T>> findById(Class<T> entityClass, Object id) {
        return dataAccessPort.findById(entityClass, id)
                .map(ApiResponse::success)
                .switchIfEmpty(Mono.just(ApiResponse.error(404, "记录不存在")));
    }

    public <T> Mono<ApiResponse<Flux<T>>> findAll(Class<T> entityClass, Map<String, Object> filters,
                                                   int page, int size) {
        return Mono.just(ApiResponse.success(
                dataAccessPort.findAll(entityClass, filters, page, size)
        ));
    }

    public <T> Mono<ApiResponse<T>> save(T entity) {
        return dataAccessPort.save(entity)
                .map(ApiResponse::success)
                .onErrorResume(e -> {
                    logger.error("保存数据失败", e, Map.of("entity", entity.getClass().getSimpleName()));
                    return Mono.just(ApiResponse.error(500, "保存失败: " + e.getMessage()));
                });
    }

    public <T> Mono<ApiResponse<T>> update(T entity) {
        return dataAccessPort.update(entity)
                .map(ApiResponse::success)
                .onErrorResume(e -> {
                    logger.error("更新数据失败", e, Map.of("entity", entity.getClass().getSimpleName()));
                    return Mono.just(ApiResponse.error(500, "更新失败: " + e.getMessage()));
                });
    }

    public <T> Mono<ApiResponse<Void>> delete(Class<T> entityClass, Object id) {
        return dataAccessPort.delete(entityClass, id)
                .then(Mono.just(ApiResponse.success(null)))
                .onErrorResume(e -> {
                    logger.error("删除数据失败", e, Map.of("id", id));
                    return Mono.just(ApiResponse.error(500, "删除失败: " + e.getMessage()));
                });
    }

    public Mono<ApiResponse<Void>> migrateSchema(String targetVersion) {
        return dataAccessPort.migrateSchema(targetVersion)
                .then(Mono.just(ApiResponse.success(null)))
                .onErrorResume(e -> {
                    logger.error("Schema迁移失败", e, Map.of("targetVersion", targetVersion));
                    return Mono.just(ApiResponse.error(500, "迁移失败: " + e.getMessage()));
                });
    }

    public Mono<ApiResponse<String>> getCurrentSchemaVersion() {
        return Mono.just(ApiResponse.success(dataAccessPort.getCurrentSchemaVersion()));
    }

    public Mono<ApiResponse<Flux<String>>> getMigrationHistory() {
        return Mono.just(ApiResponse.success(dataAccessPort.getMigrationHistory()));
    }
}
