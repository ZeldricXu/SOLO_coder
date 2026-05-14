package com.projmanage.repository;

import com.projmanage.model.Project;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ProjectRepository extends JpaRepository<Project, String> {
    List<Project> findByProjectOwner(String projectOwner);
    List<Project> findByProjectStatus(String status);
}
