package com.datamigrate.repository;

import com.datamigrate.entity.DiffRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DiffRecordRepository extends JpaRepository<DiffRecord, Long> {

    List<DiffRecord> findByVerifyId(String verifyId);

    List<DiffRecord> findByTaskId(String taskId);

    long countByVerifyId(String verifyId);
}
