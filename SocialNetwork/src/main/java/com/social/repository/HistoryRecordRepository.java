package com.social.repository;

import com.social.entity.HistoryRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface HistoryRecordRepository extends JpaRepository<HistoryRecord, Long> {
    Optional<HistoryRecord> findByHistoryId(String historyId);
    List<HistoryRecord> findByUserIdOrderByRecordTimeDesc(String userId);
    List<HistoryRecord> findByUserIdAndRecordTypeOrderByRecordTimeDesc(String userId, String recordType);
}
