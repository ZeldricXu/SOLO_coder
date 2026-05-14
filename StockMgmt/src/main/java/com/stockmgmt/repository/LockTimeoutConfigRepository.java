package com.stockmgmt.repository;

import com.stockmgmt.entity.LockTimeoutConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface LockTimeoutConfigRepository extends JpaRepository<LockTimeoutConfig, Long> {

    List<LockTimeoutConfig> findByEnabledTrue();

    Optional<LockTimeoutConfig> findByProductIdAndWarehouseIdAndEnabledTrue(
            String productId, String warehouseId);

    Optional<LockTimeoutConfig> findByProductIdAndEnabledTrue(String productId);

    List<LockTimeoutConfig> findByUrgencyLevelAndEnabledTrue(String urgencyLevel);

    Optional<LockTimeoutConfig> findFirstByUrgencyLevelAndEnabledTrueOrderByIdDesc(
            String urgencyLevel);

    @Query("SELECT c FROM LockTimeoutConfig c WHERE c.productId = :productId " +
           "AND c.warehouseId = :warehouseId AND c.enabled = true")
    Optional<LockTimeoutConfig> findSpecificConfig(
            @Param("productId") String productId,
            @Param("warehouseId") String warehouseId);

    @Query("SELECT c FROM LockTimeoutConfig c WHERE c.productId = :productId " +
           "AND c.warehouseId IS NULL AND c.enabled = true")
    Optional<LockTimeoutConfig> findProductLevelConfig(
            @Param("productId") String productId);

    @Query("SELECT c FROM LockTimeoutConfig c WHERE c.productId IS NULL " +
           "AND c.warehouseId = :warehouseId AND c.enabled = true")
    Optional<LockTimeoutConfig> findWarehouseLevelConfig(
            @Param("warehouseId") String warehouseId);

    @Query("SELECT c FROM LockTimeoutConfig c WHERE c.productId IS NULL " +
           "AND c.warehouseId IS NULL AND c.urgencyLevel = :urgencyLevel AND c.enabled = true")
    Optional<LockTimeoutConfig> findUrgencyLevelDefaultConfig(
            @Param("urgencyLevel") String urgencyLevel);

    List<LockTimeoutConfig> findByWarehouseIdAndEnabledTrue(String warehouseId);
}
