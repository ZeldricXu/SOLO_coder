package com.stockmgmt.repository;

import com.stockmgmt.entity.WarningThresholdConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface WarningThresholdConfigRepository extends JpaRepository<WarningThresholdConfig, Long> {

    List<WarningThresholdConfig> findByEnabledTrue();

    List<WarningThresholdConfig> findByConfigTypeAndEnabledTrue(String configType);

    Optional<WarningThresholdConfig> findByProductIdAndWarehouseIdAndEnabledTrue(
            String productId, String warehouseId);

    Optional<WarningThresholdConfig> findBySkuIdAndWarehouseIdAndEnabledTrue(
            String skuId, String warehouseId);

    List<WarningThresholdConfig> findByWarehouseIdAndEnabledTrue(String warehouseId);

    List<WarningThresholdConfig> findByProductIdAndEnabledTrue(String productId);

    @Query("SELECT c FROM WarningThresholdConfig c WHERE " +
           "(c.productId = :productId OR c.productId IS NULL) AND " +
           "(c.warehouseId = :warehouseId OR c.warehouseId IS NULL) AND " +
           "c.enabled = true ORDER BY c.priority DESC")
    List<WarningThresholdConfig> findMatchingConfigsOrdered(
            @Param("productId") String productId,
            @Param("warehouseId") String warehouseId);

    @Query("SELECT c FROM WarningThresholdConfig c WHERE " +
           "(c.skuId = :skuId OR c.skuId IS NULL) AND " +
           "(c.productId = :productId OR c.productId IS NULL) AND " +
           "(c.warehouseId = :warehouseId OR c.warehouseId IS NULL) AND " +
           "c.enabled = true ORDER BY c.priority DESC")
    List<WarningThresholdConfig> findMatchingConfigsWithSkuOrdered(
            @Param("skuId") String skuId,
            @Param("productId") String productId,
            @Param("warehouseId") String warehouseId);

    @Query("SELECT c FROM WarningThresholdConfig c WHERE c.configType = 'GLOBAL' AND c.enabled = true")
    Optional<WarningThresholdConfig> findGlobalConfig();

    @Query("SELECT c FROM WarningThresholdConfig c WHERE c.configType = :configType AND c.enabled = true")
    List<WarningThresholdConfig> findByConfigType(@Param("configType") String configType);
}
