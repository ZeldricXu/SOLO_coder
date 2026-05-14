package com.datamigrate.repository;

import com.datamigrate.entity.VerifyRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface VerifyRecordRepository extends JpaRepository<VerifyRecord, Long> {

    Optional<VerifyRecord> findByVerifyId(String verifyId);

    List<VerifyRecord> findByTaskIdOrderByVerifiedAtDesc(String taskId);

    @Query("SELECT v FROM VerifyRecord v WHERE v.taskId = ?1 ORDER BY v.createdAt DESC")
    List<VerifyRecord> findLatestByTaskId(String taskId);

    Optional<VerifyRecord> findTopByTaskIdOrderByCreatedAtDesc(String taskId);
}
