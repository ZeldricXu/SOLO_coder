package com.recruitment.repository;

import com.recruitment.model.Workflow;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface WorkflowRepository extends JpaRepository<Workflow, String> {
    Optional<Workflow> findByWorkflowId(String workflowId);
    Optional<Workflow> findByIsDefaultTrue();
    Optional<Workflow> findByPositionTypeAndIsDefaultTrue(String positionType);
    List<Workflow> findByPositionType(String positionType);
    boolean existsByWorkflowId(String workflowId);
}
