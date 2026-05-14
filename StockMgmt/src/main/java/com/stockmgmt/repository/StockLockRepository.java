package com.stockmgmt.repository;

import com.stockmgmt.entity.StockLock;
import com.stockmgmt.enums.LockStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import javax.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface StockLockRepository extends JpaRepository<StockLock, String>, JpaSpecificationExecutor<StockLock> {

    List<StockLock> findByStockId(String stockId);

    List<StockLock> findByStockIdAndStatus(String stockId, LockStatus status);

    List<StockLock> findByReferenceNo(String referenceNo);

    List<StockLock> findByStatus(LockStatus status);

    Optional<StockLock> findFirstByReferenceNoAndStatusOrderByLockedAtDesc(String referenceNo, LockStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT l FROM StockLock l WHERE l.lockId = :lockId")
    Optional<StockLock> findByIdWithLock(@Param("lockId") String lockId);

    @Query("SELECT SUM(l.lockedQuantity) FROM StockLock l WHERE l.stockId = :stockId AND l.status = :status")
    Integer sumLockedQuantityByStockIdAndStatus(@Param("stockId") String stockId, @Param("status") LockStatus status);

    @Query("SELECT l FROM StockLock l WHERE l.status = :status AND l.expireTime IS NOT NULL AND l.expireTime <= :now")
    List<StockLock> findExpiredLocks(@Param("status") LockStatus status, @Param("now") LocalDateTime now);

    @Query("SELECT COUNT(l) FROM StockLock l WHERE l.status = :status")
    long countByStatus(@Param("status") LockStatus status);
}
