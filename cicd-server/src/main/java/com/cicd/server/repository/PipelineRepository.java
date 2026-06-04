package com.cicd.server.repository;

import com.cicd.server.entity.Pipeline;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface PipelineRepository extends JpaRepository<Pipeline, Long> {

    List<Pipeline> findByProjectId(Long projectId);

    List<Pipeline> findByProjectIdAndIsActiveTrue(Long projectId);

    Optional<Pipeline> findByProjectIdAndName(Long projectId, String name);

    @Query("SELECT p FROM Pipeline p WHERE p.project.gitRepoUrl = ?1")
    List<Pipeline> findByGitRepoUrl(String gitRepoUrl);
}
