package com.projmanage.repository;

import com.projmanage.model.MilestoneReminderConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MilestoneReminderConfigRepository extends JpaRepository<MilestoneReminderConfig, String> {
    Optional<MilestoneReminderConfig> findByMilestoneId(String milestoneId);
    List<MilestoneReminderConfig> findByProjectId(String projectId);
    List<MilestoneReminderConfig> findByProjectIdAndIsActive(String projectId, Boolean isActive);
}
