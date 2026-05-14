package com.projectcollab.repository;

import com.projectcollab.entity.HistoryRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface HistoryRecordRepository extends JpaRepository<HistoryRecord, String> {
    List<HistoryRecord> findByProjectIdOrderByCreatedAtDesc(String projectId);
    List<HistoryRecord> findByTaskId(String taskId);
    List<HistoryRecord> findByDocId(String docId);
    List<HistoryRecord> findByUserId(String userId);
}
