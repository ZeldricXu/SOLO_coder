package com.taskscheduler.repository;

import com.taskscheduler.entity.FailRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface FailRecordRepository extends JpaRepository<FailRecord, Long> {

    List<FailRecord> findByTaskIdOrderByCreatedAtDesc(String taskId);

    Optional<FailRecord> findByExecuteId(String executeId);

    List<FailRecord> findByStatus(String status);

    @Query("SELECT f FROM FailRecord f WHERE f.status = 'retrying' AND f.nextRetryTime <= :now ORDER BY f.nextRetryTime ASC")
    List<FailRecord> findPendingRetries(@Param("now") LocalDateTime now);

    @Query("SELECT COUNT(f) FROM FailRecord f WHERE f.taskId = :taskId AND f.status = 'failed'")
    long countFailedRecords(@Param("taskId") String taskId);
}
