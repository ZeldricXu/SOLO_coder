package com.cicd.server.repository;

import com.cicd.server.entity.StageExecution;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface StageExecutionRepository extends JpaRepository<StageExecution, Long> {

    List<StageExecution> findByExecutionId(Long executionId);
}
