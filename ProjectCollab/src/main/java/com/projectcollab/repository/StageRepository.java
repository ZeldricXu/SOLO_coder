package com.projectcollab.repository;

import com.projectcollab.entity.Stage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface StageRepository extends JpaRepository<Stage, String> {
    List<Stage> findByProject_ProjectIdOrderByStageOrderAsc(String projectId);
    Optional<Stage> findByProject_ProjectIdAndStageCode(String projectId, String stageCode);
    Optional<Stage> findByProject_ProjectIdAndStageStatusOrderByStageOrderAsc(String projectId, String status);
}
