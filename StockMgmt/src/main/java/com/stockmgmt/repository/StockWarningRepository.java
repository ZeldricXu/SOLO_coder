package com.stockmgmt.repository;

import com.stockmgmt.entity.StockWarning;
import com.stockmgmt.enums.WarningStatus;
import com.stockmgmt.enums.WarningType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface StockWarningRepository extends JpaRepository<StockWarning, String>, JpaSpecificationExecutor<StockWarning> {

    List<StockWarning> findByStockId(String stockId);

    List<StockWarning> findByWarningType(WarningType warningType);

    List<StockWarning> findByStatus(WarningStatus status);

    List<StockWarning> findByWarningTypeAndStatus(WarningType warningType, WarningStatus status);

    List<StockWarning> findByProductId(String productId);

    Optional<StockWarning> findFirstByStockIdAndWarningTypeAndStatusOrderByTriggeredAtDesc(
            String stockId, WarningType warningType, WarningStatus status);

    @Query("SELECT w FROM StockWarning w WHERE w.status = :status AND w.warningType = :warningType")
    List<StockWarning> findActiveWarningsByType(@Param("status") WarningStatus status,
                                                @Param("warningType") WarningType warningType);

    @Query("SELECT COUNT(w) FROM StockWarning w WHERE w.status = :status")
    long countByStatus(@Param("status") WarningStatus status);

    @Query("SELECT w FROM StockWarning w WHERE w.triggeredAt >= :startTime AND w.triggeredAt <= :endTime ORDER BY w.triggeredAt DESC")
    List<StockWarning> findByTimeRange(@Param("startTime") LocalDateTime startTime,
                                        @Param("endTime") LocalDateTime endTime);
}
