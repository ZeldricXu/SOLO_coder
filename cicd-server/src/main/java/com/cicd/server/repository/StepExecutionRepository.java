package com.cicd.server.repository;

import com.cicd.server.entity.StepExecution;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface StepExecutionRepository extends JpaRepository<StepExecution, Long> {

    List<StepExecution> findByJobExecutionId(Long jobExecutionId);

    List<StepExecution> findByJobExecutionIdOrderByStepOrder(Long jobExecutionId);
}
