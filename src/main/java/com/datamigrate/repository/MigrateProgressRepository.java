package com.datamigrate.repository;

import com.datamigrate.entity.MigrateProgress;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface MigrateProgressRepository extends JpaRepository<MigrateProgress, Long> {

    Optional<MigrateProgress> findByTaskId(String taskId);

    Optional<MigrateProgress> findByProgressId(String progressId);
}
