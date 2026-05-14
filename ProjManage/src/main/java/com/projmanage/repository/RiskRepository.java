package com.projmanage.repository;

import com.projmanage.model.Risk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RiskRepository extends JpaRepository<Risk, String> {
    List<Risk> findByProjectId(String projectId);
    List<Risk> findByTaskId(String taskId);
    List<Risk> findByProjectIdAndRiskStatus(String projectId, String riskStatus);
}
