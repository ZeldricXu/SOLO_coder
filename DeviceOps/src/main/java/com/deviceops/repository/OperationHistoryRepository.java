package com.deviceops.repository;

import com.deviceops.entity.OperationHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface OperationHistoryRepository extends JpaRepository<OperationHistory, String> {

    List<OperationHistory> findByDeviceIdOrderByCreatedAtDesc(String deviceId);

    List<OperationHistory> findByOperatorIdOrderByCreatedAtDesc(String operatorId);

    List<OperationHistory> findByTaskId(String taskId);

    List<OperationHistory> findByFaultId(String faultId);

    List<OperationHistory> findByCreatedAtBetweenOrderByCreatedAtDesc(LocalDateTime start, LocalDateTime end);

    List<OperationHistory> findByOperationTypeOrderByCreatedAtDesc(String operationType);
}
