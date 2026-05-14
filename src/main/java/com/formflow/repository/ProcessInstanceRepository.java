package com.formflow.repository;

import com.formflow.entity.ProcessInstance;
import com.formflow.enums.ProcessInstanceStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface ProcessInstanceRepository extends JpaRepository<ProcessInstance, Long> {

    Optional<ProcessInstance> findByInstanceId(String instanceId);

    Optional<ProcessInstance> findByFormId(String formId);

    List<ProcessInstance> findByProcessId(String processId);

    List<ProcessInstance> findBySubmitterId(String submitterId);

    List<ProcessInstance> findByInstanceStatus(ProcessInstanceStatus status);

    @Query("SELECT p FROM ProcessInstance p WHERE p.processId = :processId AND p.instanceStatus = :status")
    List<ProcessInstance> findByProcessIdAndInstanceStatus(@Param("processId") String processId, @Param("status") ProcessInstanceStatus status);

    @Query("SELECT COUNT(p) FROM ProcessInstance p WHERE p.processId = :processId")
    Long countByProcessId(@Param("processId") String processId);

    @Query("SELECT COUNT(p) FROM ProcessInstance p WHERE p.processId = :processId AND p.instanceStatus = :status")
    Long countByProcessIdAndInstanceStatus(@Param("processId") String processId, @Param("status") ProcessInstanceStatus status);

    @Query("SELECT COUNT(p) FROM ProcessInstance p WHERE p.startTime BETWEEN :startTime AND :endTime")
    Long countByStartTimeBetween(@Param("startTime") LocalDateTime startTime, @Param("endTime") LocalDateTime endTime);

    @Query("SELECT COUNT(p) FROM ProcessInstance p WHERE p.instanceStatus = :status")
    Long countByInstanceStatus(@Param("status") ProcessInstanceStatus status);
}
