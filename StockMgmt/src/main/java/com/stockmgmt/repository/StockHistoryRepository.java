package com.stockmgmt.repository;

import com.stockmgmt.entity.StockHistory;
import com.stockmgmt.enums.OperationType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface StockHistoryRepository extends JpaRepository<StockHistory, String>, JpaSpecificationExecutor<StockHistory> {

    List<StockHistory> findByStockIdOrderByOperationTimeDesc(String stockId);

    List<StockHistory> findByProductIdOrderByOperationTimeDesc(String productId);

    List<StockHistory> findByOperationType(OperationType operationType);

    List<StockHistory> findByReferenceNo(String referenceNo);

    @Query("SELECT h FROM StockHistory h WHERE h.stockId = :stockId AND h.operationTime >= :startTime AND h.operationTime <= :endTime ORDER BY h.operationTime DESC")
    List<StockHistory> findByStockIdAndTimeRange(@Param("stockId") String stockId,
                                                  @Param("startTime") LocalDateTime startTime,
                                                  @Param("endTime") LocalDateTime endTime);

    @Query("SELECT SUM(h.quantityChange) FROM StockHistory h WHERE h.operationType = :operationType AND h.operationTime >= :startTime AND h.operationTime <= :endTime")
    Integer sumQuantityChangeByTypeAndTimeRange(@Param("operationType") OperationType operationType,
                                                 @Param("startTime") LocalDateTime startTime,
                                                 @Param("endTime") LocalDateTime endTime);

    @Query("SELECT COUNT(h) FROM StockHistory h WHERE h.operationType = :operationType AND h.operationTime >= :startTime AND h.operationTime <= :endTime")
    long countByTypeAndTimeRange(@Param("operationType") OperationType operationType,
                                  @Param("startTime") LocalDateTime startTime,
                                  @Param("endTime") LocalDateTime endTime);
}
