package com.cicd.server.repository;

import com.cicd.common.enums.PipelineStatus;
import com.cicd.server.entity.Deployment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface DeploymentRepository extends JpaRepository<Deployment, Long> {

    Page<Deployment> findByProjectId(Long projectId, Pageable pageable);

    Page<Deployment> findByProjectIdAndEnvironmentId(Long projectId, Long environmentId, Pageable pageable);

    @Query("SELECT MAX(d.deploymentNumber) FROM Deployment d WHERE d.project.id = ?1 AND d.environment.id = ?2")
    Integer findMaxDeploymentNumber(Long projectId, Long environmentId);

    @Query("SELECT d FROM Deployment d WHERE d.project.id = ?1 AND d.environment.id = ?2 ORDER BY d.createdAt DESC")
    List<Deployment> findLatestByProjectAndEnvironment(Long projectId, Long environmentId, Pageable pageable);

    Optional<Deployment> findFirstByProjectIdAndEnvironmentIdAndStatusOrderByCreatedAtDesc(Long projectId, Long environmentId, String status);

    @Query("SELECT d FROM Deployment d WHERE d.project.id = ?1 AND d.createdAt BETWEEN ?2 AND ?3")
    List<Deployment> findByProjectIdAndDateRange(Long projectId, LocalDateTime start, LocalDateTime end);

    @Query("SELECT COUNT(d) FROM Deployment d WHERE d.project.id = ?1 AND d.status = 'FAILED' AND d.createdAt >= ?2")
    Long countFailedDeployments(Long projectId, LocalDateTime since);

    @Query("SELECT COUNT(d) > 0 FROM Deployment d " +
           "WHERE d.version = :version AND d.appName = :name " +
           "AND d.status IN (:statuses)")
    boolean isArtifactInUse(@Param("name") String name, @Param("version") String version,
                            @Param("statuses") List<PipelineStatus> statuses);
}
