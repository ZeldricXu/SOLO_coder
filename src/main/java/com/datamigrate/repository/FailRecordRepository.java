package com.datamigrate.repository;

import com.datamigrate.common.FailStatus;
import com.datamigrate.entity.FailRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface FailRecordRepository extends JpaRepository<FailRecord, Long> {

    List<FailRecord> findByTaskId(String taskId);

    List<FailRecord> findByTaskIdAndStatus(String taskId, FailStatus status);

    @Query("SELECT f FROM FailRecord f WHERE f.status = 'PENDING_RETRY' AND f.nextRetryAt <= ?1")
    List<FailRecord> findPendingRetryRecords(LocalDateTime now);

    long countByTaskIdAndStatus(String taskId, FailStatus status);
}
