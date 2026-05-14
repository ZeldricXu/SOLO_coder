package com.mobilestore.repository;

import com.mobilestore.entity.ApprovalLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ApprovalLogRepository extends JpaRepository<ApprovalLog, String> {

    List<ApprovalLog> findByVersionIdOrderByCreatedAtDesc(String versionId);
}
