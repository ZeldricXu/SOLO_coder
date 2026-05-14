package com.datamigrate.repository;

import com.datamigrate.common.LogLevel;
import com.datamigrate.common.LogType;
import com.datamigrate.entity.MigrateLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface MigrateLogRepository extends JpaRepository<MigrateLog, Long> {

    List<MigrateLog> findByTaskIdOrderByLogTimeDesc(String taskId);

    List<MigrateLog> findByTaskIdAndLogTypeOrderByLogTimeDesc(String taskId, LogType logType);

    List<MigrateLog> findByTaskIdAndLogLevelOrderByLogTimeDesc(String taskId, LogLevel logLevel);

    List<MigrateLog> findByLogTimeBetweenOrderByLogTimeDesc(LocalDateTime start, LocalDateTime end);

    List<MigrateLog> findByTaskIdOrderByLogTimeAsc(String taskId);
}
