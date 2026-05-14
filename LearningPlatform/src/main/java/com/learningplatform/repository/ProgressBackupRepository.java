
package com.learningplatform.repository;

import com.learningplatform.entity.ProgressBackup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface ProgressBackupRepository extends JpaRepository<ProgressBackup, String> {

    List<ProgressBackup> findByProgressIdOrderByBackupTimeDesc(String progressId);

    List<ProgressBackup> findByStudentIdOrderByBackupTimeDesc(String studentId);

    List<ProgressBackup> findByCourseIdOrderByBackupTimeDesc(String courseId);

    List<ProgressBackup> findByProgressIdAndBackupTimeBetweenOrderByBackupTimeDesc(
            String progressId, LocalDateTime start, LocalDateTime end);

    Optional<ProgressBackup> findFirstByProgressIdOrderByBackupTimeDesc(String progressId);

    long countByProgressId(String progressId);

    void deleteByBackupTimeBefore(LocalDateTime cutoffTime);
}
