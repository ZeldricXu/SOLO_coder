package com.cicd.server.repository;

import com.cicd.server.entity.Project;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface ProjectRepository extends JpaRepository<Project, Long> {

    Optional<Project> findByName(String name);

    Optional<Project> findByGitRepoUrl(String gitRepoUrl);

    List<Project> findByIsActiveTrue();

    @Query("SELECT p FROM Project p JOIN p.userRoles ur JOIN ur.user u WHERE u.username = ?1")
    List<Project> findByUsername(String username);
}
