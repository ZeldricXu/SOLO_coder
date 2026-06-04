package com.cicd.server.repository;

import com.cicd.common.enums.PipelineStatus;
import com.cicd.server.entity.JobExecution;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface JobExecutionRepository extends JpaRepository<JobExecution, Long> {

    List<JobExecution> findByStageExecutionId(Long stageExecutionId);

    List<JobExecution> findByStatus(PipelineStatus status);

    @Query("SELECT je FROM JobExecution je WHERE je.status = 'PENDING' ORDER BY je.createdAt ASC")
    List<JobExecution> findPendingJobs();

    @Query("SELECT je FROM JobExecution je WHERE je.runnerId = ?1 AND je.status IN ('PENDING', 'RUNNING')")
    List<JobExecution> findJobsByRunner(Long runnerId);
}
