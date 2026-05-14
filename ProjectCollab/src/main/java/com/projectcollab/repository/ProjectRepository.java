package com.projectcollab.repository;

import com.projectcollab.entity.Project;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ProjectRepository extends JpaRepository<Project, String> {
    List<Project> findByProjectStatus(String status);
    List<Project> findByProjectType(String type);
}
