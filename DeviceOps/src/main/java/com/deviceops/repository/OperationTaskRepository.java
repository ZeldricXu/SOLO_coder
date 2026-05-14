package com.deviceops.repository;

import com.deviceops.entity.OperationTask;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface OperationTaskRepository extends JpaRepository<OperationTask, String> {

    List<OperationTask> findByDeviceIdOrderByTaskTimeDesc(String deviceId);

    List<OperationTask> findByFaultId(String faultId);

    List<OperationTask> findByOperatorId(String operatorId);

    List<OperationTask> findByTaskStatus(String taskStatus);

    List<OperationTask> findByTaskTimeBetween(LocalDateTime start, LocalDateTime end);

    long countByTaskStatus(String taskStatus);

    long countByTaskTimeBetween(LocalDateTime start, LocalDateTime end);
}
