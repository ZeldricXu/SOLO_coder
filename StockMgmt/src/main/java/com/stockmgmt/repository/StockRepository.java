package com.stockmgmt.repository;

import com.stockmgmt.entity.Stock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import javax.persistence.LockModeType;
import java.util.List;
import java.util.Optional;

@Repository
public interface StockRepository extends JpaRepository<Stock, String>, JpaSpecificationExecutor<Stock> {

    Optional<Stock> findByProductIdAndWarehouseId(String productId, String warehouseId);

    Optional<Stock> findByProductId(String productId);

    List<Stock> findByWarehouseId(String warehouseId);

    List<Stock> findByLocationId(String locationId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM Stock s WHERE s.stockId = :stockId")
    Optional<Stock> findByIdWithLock(@Param("stockId") String stockId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM Stock s WHERE s.productId = :productId AND s.warehouseId = :warehouseId")
    Optional<Stock> findByProductIdAndWarehouseIdWithLock(@Param("productId") String productId, @Param("warehouseId") String warehouseId);

    @Query("SELECT s FROM Stock s WHERE s.currentQuantity <= s.warningThreshold")
    List<Stock> findLowStock();

    @Query("SELECT s FROM Stock s WHERE s.currentQuantity >= s.overstockThreshold")
    List<Stock> findOverstock();

    @Query("SELECT COUNT(s) FROM Stock s WHERE s.warehouseId = :warehouseId")
    long countByWarehouseId(@Param("warehouseId") String warehouseId);
}
