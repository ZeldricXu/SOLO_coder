package com.stockmgmt.repository;

import com.stockmgmt.entity.WarningAggregationConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface WarningAggregationConfigRepository extends JpaRepository<WarningAggregationConfig, Long> {

    List<WarningAggregationConfig> findByEnabledTrue();

    Optional<WarningAggregationConfig> findByWarningLevelAndWarningTypeAndEnabledTrue(
            String warningLevel, String warningType);

    Optional<WarningAggregationConfig> findByWarningLevelAndWarningTypeAndProductIdAndEnabledTrue(
            String warningLevel, String warningType, String productId);

    Optional<WarningAggregationConfig> findByWarningLevelAndWarningTypeAndWarehouseIdAndEnabledTrue(
            String warningLevel, String warningType, String warehouseId);

    List<WarningAggregationConfig> findByWarningLevelAndEnabledTrue(String warningLevel);

    List<WarningAggregationConfig> findByWarningTypeAndEnabledTrue(String warningType);

    @Query("SELECT c FROM WarningAggregationConfig c WHERE c.warningLevel = :warningLevel " +
           "AND c.warningType = :warningType AND c.productId = :productId " +
           "AND c.warehouseId = :warehouseId AND c.enabled = true")
    Optional<WarningAggregationConfig> findSpecificConfig(
            @Param("warningLevel") String warningLevel,
            @Param("warningType") String warningType,
            @Param("productId") String productId,
            @Param("warehouseId") String warehouseId);

    @Query("SELECT c FROM WarningAggregationConfig c WHERE c.warningLevel = :warningLevel " +
           "AND c.warningType = :warningType AND c.productId = :productId " +
           "AND c.warehouseId IS NULL AND c.enabled = true")
    Optional<WarningAggregationConfig> findProductLevelConfig(
            @Param("warningLevel") String warningLevel,
            @Param("warningType") String warningType,
            @Param("productId") String productId);

    @Query("SELECT c FROM WarningAggregationConfig c WHERE c.warningLevel = :warningLevel " +
           "AND c.warningType = :warningType AND c.productId IS NULL " +
           "AND c.warehouseId = :warehouseId AND c.enabled = true")
    Optional<WarningAggregationConfig> findWarehouseLevelConfig(
            @Param("warningLevel") String warningLevel,
            @Param("warningType") String warningType,
            @Param("warehouseId") String warehouseId);

    @Query("SELECT c FROM WarningAggregationConfig c WHERE c.warningLevel = :warningLevel " +
           "AND c.warningType = :warningType AND c.productId IS NULL " +
           "AND c.warehouseId IS NULL AND c.enabled = true")
    Optional<WarningAggregationConfig> findLevelTypeDefaultConfig(
            @Param("warningLevel") String warningLevel,
            @Param("warningType") String warningType);
}
