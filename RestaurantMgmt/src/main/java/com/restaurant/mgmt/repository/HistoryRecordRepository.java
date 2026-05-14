package com.restaurant.mgmt.repository;

import com.restaurant.mgmt.model.HistoryRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface HistoryRecordRepository extends JpaRepository<HistoryRecord, String> {
    List<HistoryRecord> findByRecordType(String recordType);
    List<HistoryRecord> findByReferenceId(String referenceId);
    List<HistoryRecord> findByCreatedAtBetween(LocalDateTime startTime, LocalDateTime endTime);
    List<HistoryRecord> findByRecordTypeAndCreatedAtBetween(String recordType, LocalDateTime startTime, LocalDateTime endTime);
}
