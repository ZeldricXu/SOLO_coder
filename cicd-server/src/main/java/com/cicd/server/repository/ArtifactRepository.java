package com.cicd.server.repository;

import com.cicd.common.enums.ArtifactType;
import com.cicd.server.entity.Artifact;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface ArtifactRepository extends JpaRepository<Artifact, Long> {

    Page<Artifact> findByProjectId(Long projectId, Pageable pageable);

    Page<Artifact> findByProjectIdAndType(Long projectId, ArtifactType type, Pageable pageable);

    List<Artifact> findByProjectIdAndName(Long projectId, String name);

    Optional<Artifact> findByProjectIdAndNameAndVersion(Long projectId, String name, String version);

    Optional<Artifact> findByGitCommitSha(String gitCommitSha);

    @Query("SELECT a FROM Artifact a WHERE a.expiresAt < ?1 AND a.isPinned = false AND a.cleanupStatus = 'NONE'")
    List<Artifact> findExpiredArtifacts(LocalDateTime now);

    @Query("SELECT a FROM Artifact a WHERE a.project.id = ?1 AND a.createdAt < ?2 AND a.createdAt >= ?3 AND a.isPinned = false AND a.cleanupStatus = 'NONE' ORDER BY a.createdAt DESC")
    List<Artifact> findArtifactsForCleanup(Long projectId, LocalDateTime olderThan, LocalDateTime newerThan);

    @Query("SELECT a FROM Artifact a WHERE a.project.id = ?1 AND a.name = ?2 ORDER BY a.createdAt DESC")
    List<Artifact> findLatestByName(Long projectId, String name, Pageable pageable);

    @Query("SELECT a FROM Artifact a WHERE a.cleanupStatus = :status")
    List<Artifact> findByCleanupStatus(@Param("status") String status);

    @Query("SELECT a FROM Artifact a WHERE a.cleanupStatus = 'PENDING' AND a.isPinned = false")
    List<Artifact> findPendingCleanup();

    @Modifying
    @Query("UPDATE Artifact a SET a.cleanupStatus = :status WHERE a.id IN :ids")
    void updateCleanupStatusByIds(@Param("ids") List<Long> ids, @Param("status") String status);

    @Modifying
    @Query("UPDATE Artifact a SET a.cleanupStatus = :status WHERE a.id = :id")
    void updateCleanupStatus(@Param("id") Long id, @Param("status") String status);
}
