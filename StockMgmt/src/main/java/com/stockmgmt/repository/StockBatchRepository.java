package com.stockmgmt.repository;

import com.stockmgmt.entity.StockBatch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface StockBatchRepository extends JpaRepository<StockBatch, String>, JpaSpecificationExecutor<StockBatch> {

    Optional<StockBatch> findByBatchNo(String batchNo);

    List<StockBatch> findByProductId(String productId);

    List<StockBatch> findByProductIdAndWarehouseId(String productId, String warehouseId);

    List<StockBatch> findByWarehouseId(String warehouseId);

    @Query("SELECT b FROM StockBatch b WHERE b.expireDate IS NOT NULL AND b.expireDate <= :expireDate AND b.remainingQuantity > 0 ORDER BY b.expireDate ASC")
    List<StockBatch> findExpiringBatches(@Param("expireDate") LocalDate expireDate);

    @Query("SELECT b FROM StockBatch b WHERE b.productId = :productId AND b.remainingQuantity > 0 ORDER BY b.productionDate ASC")
    List<StockBatch> findAvailableBatchesByProductId(@Param("productId") String productId);
}
