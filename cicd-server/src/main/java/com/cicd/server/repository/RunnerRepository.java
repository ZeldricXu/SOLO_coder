package com.cicd.server.repository;

import com.cicd.server.entity.Runner;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface RunnerRepository extends JpaRepository<Runner, Long> {

    Optional<Runner> findByRunnerToken(String runnerToken);

    Optional<Runner> findByName(String name);

    List<Runner> findByStatus(String status);

    List<Runner> findByIsActiveTrue();

    List<Runner> findByIsActiveTrueAndIsLockedFalse();

    @Query("SELECT r FROM Runner r WHERE r.isActive = true AND r.isLocked = false AND r.status = 'ONLINE' AND r.lastHeartbeatAt >= ?1")
    List<Runner> findAvailableRunners(LocalDateTime heartbeatThreshold);

    @Query("SELECT r FROM Runner r WHERE r.lastHeartbeatAt < ?1 AND r.status != 'OFFLINE'")
    List<Runner> findStaleRunners(LocalDateTime heartbeatThreshold);

    @Query("SELECT r FROM Runner r WHERE r.tags LIKE %?1% AND r.isActive = true AND r.isLocked = false AND r.status = 'ONLINE'")
    List<Runner> findByTag(String tag);
}
