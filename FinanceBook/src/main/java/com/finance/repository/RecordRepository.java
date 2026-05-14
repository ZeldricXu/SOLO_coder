package com.finance.repository;

import com.finance.entity.Record;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface RecordRepository extends JpaRepository<Record, String> {
    List<Record> findByAccountIdOrderByRecordTimeDesc(String accountId);
    List<Record> findByAccountIdAndRecordType(String accountId, String recordType);
    List<Record> findByAccountIdAndRecordTimeBetween(String accountId, LocalDateTime startTime, LocalDateTime endTime);
    List<Record> findByAccountIdAndRecordCategory(String accountId, String category);

    @Query("SELECT COALESCE(SUM(r.recordAmount), 0) FROM Record r WHERE r.accountId = :accountId AND r.recordType = 'income' AND r.recordTime BETWEEN :startTime AND :endTime")
    BigDecimal sumIncomeByAccountIdAndTimeRange(@Param("accountId") String accountId, @Param("startTime") LocalDateTime startTime, @Param("endTime") LocalDateTime endTime);

    @Query("SELECT COALESCE(SUM(r.recordAmount), 0) FROM Record r WHERE r.accountId = :accountId AND r.recordType = 'expense' AND r.recordTime BETWEEN :startTime AND :endTime")
    BigDecimal sumExpenseByAccountIdAndTimeRange(@Param("accountId") String accountId, @Param("startTime") LocalDateTime startTime, @Param("endTime") LocalDateTime endTime);

    @Query("SELECT r.recordCategory, COALESCE(SUM(r.recordAmount), 0) FROM Record r WHERE r.accountId = :accountId AND r.recordTime BETWEEN :startTime AND :endTime GROUP BY r.recordCategory")
    List<Object[]> sumByCategoryAndTimeRange(@Param("accountId") String accountId, @Param("startTime") LocalDateTime startTime, @Param("endTime") LocalDateTime endTime);

    Long countByAccountId(String accountId);
}
