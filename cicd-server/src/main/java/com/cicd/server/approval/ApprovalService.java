package com.cicd.server.approval;

import com.cicd.common.enums.ApprovalMode;
import com.cicd.common.enums.PipelineStatus;
import com.cicd.server.entity.*;
import com.cicd.server.notification.NotificationService;
import com.cicd.server.pipeline.PipelineOrchestrator;
import com.cicd.server.repository.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class ApprovalService {

    private final ApprovalRepository approvalRepository;
    private final ApprovalDecisionRepository decisionRepository;
    private final PipelineExecutionRepository executionRepository;
    private final EnvironmentRepository environmentRepository;
    private final UserRepository userRepository;
    private final NotificationService notificationService;
    private final PipelineOrchestrator orchestrator;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Transactional
    public Approval createApproval(PipelineExecution execution, List<String> approvers,
                                   ApprovalMode mode, String environmentName, int timeoutMinutes) {
        Approval approval = new Approval();
        approval.setPipelineExecution(execution);
        approval.setTitle("部署审批 - " + execution.getPipeline().getName());
        approval.setDescription("需要审批后才能部署到 " + environmentName + " 环境");
        approval.setApprovalMode(mode);
        approval.setApproversJson(toJson(approvers));
        approval.setStatus("PENDING");
        approval.setNotificationSent(false);
        approval.setExpiresAt(LocalDateTime.now().plusMinutes(timeoutMinutes));

        if (environmentName != null) {
            Environment env = environmentRepository.findByProjectIdAndName(
                    execution.getPipeline().getProject().getId(), environmentName).orElse(null);
            approval.setEnvironment(env);
        }

        approval = approvalRepository.save(approval);

        for (String approver : approvers) {
            ApprovalDecision decision = new ApprovalDecision();
            decision.setApproval(approval);
            decision.setApprover(approver);
            decision.setStatus("PENDING");
            decisionRepository.save(decision);
        }

        notificationService.sendApprovalNotification(approval);

        return approval;
    }

    @Transactional
    @PreAuthorize("hasPermission(#approvalId, 'approval', 'approve')")
    public Approval approve(Long approvalId, String approver, String comment) {
        try {
            Approval approval = approvalRepository.findById(approvalId)
                    .orElseThrow(() -> new RuntimeException("Approval not found"));

            if (!"PENDING".equals(approval.getStatus())) {
                log.info("Approval {} is already in {} state, returning existing result (idempotent)",
                    approvalId, approval.getStatus());
                return approval;
            }

            if (isExpired(approval)) {
                approval.setStatus("EXPIRED");
                approval.setRejectedAt(LocalDateTime.now());
                approval.setDecisionComment("审批已过期");
                approvalRepository.save(approval);
                orchestrator.onApprovalCompleted(approval.getPipelineExecution().getId(), false);
                return approval;
            }

            ApprovalDecision decision = decisionRepository.findByApprovalIdAndApprover(approvalId, approver)
                    .orElseThrow(() -> new RuntimeException("Approver not found for this approval"));

            if (!"PENDING".equals(decision.getStatus())) {
                log.info("Approver {} has already made decision {} for approval {}, returning existing result (idempotent)",
                    approver, decision.getStatus(), approvalId);
                return approval;
            }

            decision.setStatus("APPROVED");
            decision.setComment(comment);
            decision.setDecidedAt(LocalDateTime.now());
            decisionRepository.save(decision);

            boolean approved = checkApprovalComplete(approval, true);
            if (approved) {
                approval.setStatus("APPROVED");
                approval.setApprovedAt(LocalDateTime.now());
                approval.setDecidedBy(approver);
                approval.setDecisionComment(comment);
                approvalRepository.save(approval);

                notificationService.sendApprovalResultNotification(approval, true, approver, comment);
                orchestrator.onApprovalCompleted(approval.getPipelineExecution().getId(), true);
            }

            return approval;
        } catch (DataIntegrityViolationException e) {
            log.info("Duplicate approval request detected for approval {} by approver {}, " +
                "silently returning existing result (idempotent)", approvalId, approver);
            return approvalRepository.findById(approvalId).orElse(null);
        }
    }

    @Transactional
    @PreAuthorize("hasPermission(#approvalId, 'approval', 'approve')")
    public Approval reject(Long approvalId, String approver, String comment) {
        try {
            Approval approval = approvalRepository.findById(approvalId)
                    .orElseThrow(() -> new RuntimeException("Approval not found"));

            if (!"PENDING".equals(approval.getStatus())) {
                log.info("Approval {} is already in {} state, returning existing result (idempotent)",
                    approvalId, approval.getStatus());
                return approval;
            }

            ApprovalDecision decision = decisionRepository.findByApprovalIdAndApprover(approvalId, approver)
                    .orElseThrow(() -> new RuntimeException("Approver not found for this approval"));

            if (!"PENDING".equals(decision.getStatus())) {
                log.info("Approver {} has already made decision {} for approval {}, returning existing result (idempotent)",
                    approver, decision.getStatus(), approvalId);
                return approval;
            }

            decision.setStatus("REJECTED");
            decision.setComment(comment);
            decision.setDecidedAt(LocalDateTime.now());
            decisionRepository.save(decision);

            approval.setStatus("REJECTED");
            approval.setRejectedAt(LocalDateTime.now());
            approval.setDecidedBy(approver);
            approval.setDecisionComment(comment);
            approvalRepository.save(approval);

            notificationService.sendApprovalResultNotification(approval, false, approver, comment);
            orchestrator.onApprovalCompleted(approval.getPipelineExecution().getId(), false);

            return approval;
        } catch (DataIntegrityViolationException e) {
            log.info("Duplicate rejection request detected for approval {} by approver {}, " +
                "silently returning existing result (idempotent)", approvalId, approver);
            return approvalRepository.findById(approvalId).orElse(null);
        }
    }

    public Approval getApproval(Long approvalId) {
        return approvalRepository.findById(approvalId).orElse(null);
    }

    public List<Approval> getPendingApprovals(String approver) {
        return approvalRepository.findByStatusAndApproverContaining("PENDING", approver);
    }

    public List<Approval> getApprovalHistory(Long projectId, int page, int size) {
        return approvalRepository.findByPipelineExecutionPipelineProjectIdOrderByCreatedAtDesc(projectId);
    }

    private boolean checkApprovalComplete(Approval approval, boolean currentApproved) {
        List<ApprovalDecision> decisions = decisionRepository.findByApprovalId(approval.getId());

        if (approval.getApprovalMode() == ApprovalMode.ANY) {
            return currentApproved;
        } else if (approval.getApprovalMode() == ApprovalMode.ALL) {
            for (ApprovalDecision d : decisions) {
                if ("PENDING".equals(d.getStatus())) return false;
                if ("REJECTED".equals(d.getStatus())) return false;
            }
            return true;
        }

        return currentApproved;
    }

    private boolean isExpired(Approval approval) {
        return approval.getExpiresAt() != null && LocalDateTime.now().isAfter(approval.getExpiresAt());
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize to JSON", e);
        }
    }

    private <T> T fromJson(String json, Class<T> clazz) {
        try {
            return objectMapper.readValue(json, clazz);
        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize from JSON", e);
        }
    }
}
