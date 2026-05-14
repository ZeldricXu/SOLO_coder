package com.survey.repository;

import com.survey.entity.HistoryRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface HistoryRecordRepository extends JpaRepository<HistoryRecord, Long> {

    List<HistoryRecord> findByBusinessTypeAndBusinessIdOrderByCreatedAtDesc(String businessType, String businessId);

    List<HistoryRecord> findByBusinessTypeOrderByCreatedAtDesc(String businessType);
}
