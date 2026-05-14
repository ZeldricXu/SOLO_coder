package com.stockmgmt.repository;

import com.stockmgmt.entity.StockCheckDiff;
import com.stockmgmt.enums.DiffHandleStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface StockCheckDiffRepository extends JpaRepository<StockCheckDiff, String>, JpaSpecificationExecutor<StockCheckDiff> {

    List<StockCheckDiff> findByCheckId(String checkId);

    List<StockCheckDiff> findByStockId(String stockId);

    List<StockCheckDiff> findByCheckIdAndHandleStatus(String checkId, DiffHandleStatus handleStatus);

    @Query("SELECT COUNT(d) FROM StockCheckDiff d WHERE d.checkId = :checkId")
    long countByCheckId(@Param("checkId") String checkId);

    @Query("SELECT COUNT(d) FROM StockCheckDiff d WHERE d.checkId = :checkId AND d.handleStatus = :status")
    long countByCheckIdAndStatus(@Param("checkId") String checkId, @Param("status") DiffHandleStatus status);
}
