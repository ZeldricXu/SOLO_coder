package com.cms.repository;

import com.cms.entity.HistoryRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface HistoryRecordRepository extends JpaRepository<HistoryRecord, String> {

    List<HistoryRecord> findByContentIdOrderByOperationTimeDesc(String contentId);

    List<HistoryRecord> findByOperatorIdOrderByOperationTimeDesc(String operatorId);

    List<HistoryRecord> findByOperationTypeOrderByOperationTimeDesc(String operationType);

    @Query("SELECT h FROM HistoryRecord h WHERE h.contentId = :contentId AND h.operationType = :operationType ORDER BY h.operationTime DESC")
    List<HistoryRecord> findByContentIdAndOperationType(@Param("contentId") String contentId, @Param("operationType") String operationType);
}
