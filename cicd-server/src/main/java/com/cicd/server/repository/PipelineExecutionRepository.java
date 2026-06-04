package com.cicd.server.repository;

import com.cicd.common.enums.PipelineStatus;
import com.cicd.server.entity.PipelineExecution;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface PipelineExecutionRepository extends JpaRepository<PipelineExecution, Long> {

    Optional<PipelineExecution> findByPipelineIdAndExecutionNumber(Long pipelineId, Integer executionNumber);

    Page<PipelineExecution> findByPipelineId(Long pipelineId, Pageable pageable);

    Page<PipelineExecution> findByProjectId(Long projectId, Pageable pageable);

    List<PipelineExecution> findByStatus(PipelineStatus status);

    @Query("SELECT MAX(pe.executionNumber) FROM PipelineExecution pe WHERE pe.pipeline.id = ?1")
    Integer findMaxExecutionNumberByPipelineId(Long pipelineId);

    @Query("SELECT COUNT(pe) FROM PipelineExecution pe WHERE pe.project.id = ?1 AND pe.createdAt >= ?2")
    Long countByProjectIdAndCreatedAtAfter(Long projectId, LocalDateTime date);

    @Query("SELECT pe FROM PipelineExecution pe WHERE pe.status IN ('RUNNING', 'PENDING')")
    List<PipelineExecution> findActiveExecutions();

    @Query("SELECT pe FROM PipelineExecution pe WHERE pe.project.id = ?1 AND pe.createdAt BETWEEN ?2 AND ?3")
    List<PipelineExecution> findByProjectIdAndDateRange(Long projectId, LocalDateTime start, LocalDateTime end);
}
