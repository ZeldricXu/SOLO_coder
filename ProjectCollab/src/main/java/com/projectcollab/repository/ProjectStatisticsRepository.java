package com.projectcollab.repository;

import com.projectcollab.entity.ProjectStatistics;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ProjectStatisticsRepository extends JpaRepository<ProjectStatistics, String> {
    Optional<ProjectStatistics> findByStatMonth(String statMonth);
}
