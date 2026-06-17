package com.enterprise.risk.storage.repository;

import com.enterprise.risk.storage.entity.ModelConfigEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ModelConfigRepository extends JpaRepository<ModelConfigEntity, String> {

    /**
     * 根据模型名称和版本查询
     *
     * @param modelName    模型名称
     * @param modelVersion 模型版本
     * @return 模型配置
     */
    Optional<ModelConfigEntity> findByModelNameAndModelVersion(String modelName, String modelVersion);

    /**
     * 查询所有启用的模型
     *
     * @return 启用的模型配置列表
     */
    List<ModelConfigEntity> findByEnabledTrue();

    /**
     * 根据模型名称查询所有版本
     *
     * @param modelName 模型名称
     * @return 模型配置列表
     */
    List<ModelConfigEntity> findByModelNameOrderByCreatedAtDesc(String modelName);

    /**
     * 查询指定模型名称的最新启用版本
     *
     * @param modelName 模型名称
     * @param enabled   是否启用
     * @return 最新模型配置
     */
    Optional<ModelConfigEntity> findFirstByModelNameAndEnabledOrderByCreatedAtDesc(String modelName, Boolean enabled);

    /**
     * 统计启用的模型数量
     *
     * @return 启用的模型数量
     */
    long countByEnabledTrue();

    /**
     * 根据模型名称检查是否存在
     *
     * @param modelName    模型名称
     * @param modelVersion 模型版本
     * @return 是否存在
     */
    boolean existsByModelNameAndModelVersion(String modelName, String modelVersion);
}
