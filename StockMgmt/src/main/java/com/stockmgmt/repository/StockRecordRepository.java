package com.stockmgmt.repository;

import com.stockmgmt.entity.StockRecord;
import com.stockmgmt.enums.OperationType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface StockRecordRepository extends JpaRepository<StockRecord, String>, JpaSpecificationExecutor<StockRecord> {

    List<StockRecord> findByStockId(String stockId);

    List<StockRecord> findByStockIdOrderByOperationTimeDesc(String stockId);

    List<StockRecord> findByOperationType(OperationType operationType);

    List<StockRecord> findByReferenceNo(String referenceNo);

    @Query("SELECT r FROM StockRecord r WHERE r.stockId = :stockId AND r.operationTime >= :startTime AND r.operationTime <= :endTime ORDER BY r.operationTime DESC")
    List<StockRecord> findByStockIdAndTimeRange(@Param("stockId") String stockId,
                                                 @Param("startTime") LocalDateTime startTime,
                                                 @Param("endTime") LocalDateTime endTime);

    @Query("SELECT SUM(r.quantity) FROM StockRecord r WHERE r.operationType = :operationType AND r.operationTime >= :startTime AND r.operationTime <= :endTime")
    Integer sumQuantityByTypeAndTimeRange(@Param("operationType") OperationType operationType,
                                           @Param("startTime") LocalDateTime startTime,
                                           @Param("endTime") LocalDateTime endTime);
}
