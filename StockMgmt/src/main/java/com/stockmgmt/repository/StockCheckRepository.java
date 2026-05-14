package com.stockmgmt.repository;

import com.stockmgmt.entity.StockCheck;
import com.stockmgmt.enums.CheckStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface StockCheckRepository extends JpaRepository<StockCheck, String>, JpaSpecificationExecutor<StockCheck> {

    Optional<StockCheck> findByCheckNo(String checkNo);

    List<StockCheck> findByWarehouseId(String warehouseId);

    List<StockCheck> findByCheckStatus(CheckStatus checkStatus);

    List<StockCheck> findByWarehouseIdAndCheckStatus(String warehouseId, CheckStatus checkStatus);

    @Query("SELECT c FROM StockCheck c WHERE c.createdAt >= :startTime AND c.createdAt <= :endTime ORDER BY c.createdAt DESC")
    List<StockCheck> findByTimeRange(@Param("startTime") LocalDateTime startTime,
                                      @Param("endTime") LocalDateTime endTime);

    @Query("SELECT COUNT(c) FROM StockCheck c WHERE c.checkStatus = :status")
    long countByStatus(@Param("status") CheckStatus status);
}
