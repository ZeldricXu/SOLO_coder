package com.datamigrate.repository;

import com.datamigrate.entity.MigrateStat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface MigrateStatRepository extends JpaRepository<MigrateStat, Long> {

    Optional<MigrateStat> findByTaskId(String taskId);

    Optional<MigrateStat> findByStatId(String statId);
}
