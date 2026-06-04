package com.cicd.server.repository;

import com.cicd.server.entity.Approval;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface ApprovalRepository extends JpaRepository<Approval, Long> {

    Optional<Approval> findByPipelineExecutionId(Long pipelineExecutionId);

    List<Approval> findByStatus(String status);

    List<Approval> findByApproversJsonContaining(String approver);
}
