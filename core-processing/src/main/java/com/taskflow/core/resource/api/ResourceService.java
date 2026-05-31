package com.taskflow.core.resource.api;

import com.taskflow.common.model.PageResult;
import com.taskflow.core.resource.domain.Resource;
import reactor.core.publisher.Mono;

/**
 * 资源服务 - 最小化接口
 * 仅定义资源管理相关的核心操作
 */
public interface ResourceService {

    /**
     * 创建资源
     * @param resource 资源定义
     * @return 创建后的资源
     */
    Mono<Resource> create(Resource resource);

    /**
     * 获取资源
     * @param tenantId 租户ID
     * @param resourceId 资源ID
     * @return 资源信息
     */
    Mono<Resource> getById(String tenantId, String resourceId);

    /**
     * 更新资源
     * @param resource 资源定义
     * @return 更新后的资源
     */
    Mono<Resource> update(Resource resource);

    /**
     * 删除资源
     * @param tenantId 租户ID
     * @param resourceId 资源ID
     */
    Mono<Void> delete(String tenantId, String resourceId);

    /**
     * 分页查询资源
     * @param tenantId 租户ID
     * @param type 资源类型（可选）
     * @param page 页码
     * @param size 每页大小
     * @return 分页结果
     */
    Mono<PageResult<Resource>> list(String tenantId, String type, int page, int size);

    /**
     * 更新资源状态
     * @param tenantId 租户ID
     * @param resourceId 资源ID
     * @param status 新状态
     * @return 更新后的资源
     */
    Mono<Resource> updateStatus(String tenantId, String resourceId, String status);
}
