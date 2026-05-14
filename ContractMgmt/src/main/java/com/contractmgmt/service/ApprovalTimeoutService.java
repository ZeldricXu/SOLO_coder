package com.contractmgmt.service;

import cn.hutool.core.util.IdUtil;
import com.contractmgmt.config.ContractConfig;
import com.contractmgmt.entity.ApprovalRecord;
import com.contractmgmt.entity.Contract;
import com.contractmgmt.entity.ReminderConfig;
import com.contractmgmt.repository.ApprovalRecordRepository;
import com.contractmgmt.repository.ContractRepository;
import com.contractmgmt.repository.ReminderConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

@Service
public class ApprovalTimeoutService {

    private static final Logger logger = LoggerFactory.getLogger(ApprovalTimeoutService.class);

    private final ApprovalRecordRepository approvalRecordRepository;
    private final ContractRepository contractRepository;
    private final ReminderConfigRepository reminderConfigRepository;
    private final ContractConfig contractConfig;

    public ApprovalTimeoutService(
            ApprovalRecordRepository approvalRecordRepository,
            ContractRepository contractRepository,
            ReminderConfigRepository reminderConfigRepository,
            ContractConfig contractConfig) {
        this.approvalRecordRepository = approvalRecordRepository;
        this.contractRepository = contractRepository;
        this.reminderConfigRepository = reminderConfigRepository;
        this.contractConfig = contractConfig;
    }

    @Transactional
    public List<TimeoutCheckResult> checkApprovalTimeouts() {
        List<TimeoutCheckResult> results = new ArrayList<>();

        ContractConfig.Timeout timeoutConfig = contractConfig.getApproval().getTimeout();
        if (timeoutConfig == null || !Boolean.TRUE.equals(timeoutConfig.getEnabled())) {
            logger.debug("审批超时检测已禁用");
            return results;
        }

        List<Contract> pendingContracts = contractRepository.findByContractStatus("pending_approval");
        logger.info("开始检测 {} 个待审批合同的超时情况", pendingContracts.size());

        for (Contract contract : pendingContracts) {
            List<ApprovalRecord> approvals = approvalRecordRepository
                    .findByContractIdAndApprovalType(contract.getContractId(), "create");

            if (approvals.isEmpty()) {
                continue;
            }

            ApprovalRecord latestApproval = approvals.get(0);
            if (!"pending".equals(latestApproval.getApprovalStatus())) {
                continue;
            }

            String urgency = contract.getUrgencyLevel() != null ? contract.getUrgencyLevel() : "normal";
            Integer timeoutHours = timeoutConfig.getTimeoutByUrgency(urgency);

            long hoursSinceCreation = ChronoUnit.HOURS.between(
                    latestApproval.getApprovalTime(), LocalDateTime.now());

            TimeoutStatus status;
            if (hoursSinceCreation >= timeoutHours) {
                status = TimeoutStatus.TIMEOUT;
            } else if (hoursSinceCreation >= timeoutHours * 0.8) {
                status = TimeoutStatus.WARNING;
            } else {
                status = TimeoutStatus.NORMAL;
            }

            TimeoutCheckResult result = new TimeoutCheckResult(
                    contract.getContractId(),
                    latestApproval.getApprovalId(),
                    latestApproval.getApprover(),
                    urgency,
                    hoursSinceCreation,
                    timeoutHours,
                    status
            );
            results.add(result);

            if (status == TimeoutStatus.WARNING || status == TimeoutStatus.TIMEOUT) {
                sendTimeoutReminder(result);
            }
        }

        logger.info("审批超时检测完成，共检测 {} 个合同，超时/警告: {}",
                pendingContracts.size(),
                results.stream().filter(r -> r.status != TimeoutStatus.NORMAL).count());

        return results;
    }

    private void sendTimeoutReminder(TimeoutCheckResult result) {
        try {
            ReminderConfig reminder = new ReminderConfig();
            reminder.setReminderId("reminder_" + IdUtil.getSnowflakeNextIdStr());
            reminder.setContractId(result.contractId);
            reminder.setReminderType("approval_timeout");
            reminder.setReminderTime(java.time.LocalDate.now());
            reminder.setReminderChannel("email");
            reminder.setReminderStatus("pending");
            reminder.setRetryCount(0);
            reminderConfigRepository.save(reminder);

            String message = buildTimeoutMessage(result);
            logger.info("发送审批超时提醒: 审批人={}, 合同={}, 状态={}",
                    result.approver, result.contractId, result.status);

        } catch (Exception e) {
            logger.error("发送审批超时提醒失败: 合同={}", result.contractId, e);
        }
    }

    private String buildTimeoutMessage(TimeoutCheckResult result) {
        String level = result.status == TimeoutStatus.TIMEOUT ? "【已超时】" : "【即将超时】";
        return String.format(
                "%s合同审批提醒: 合同 %s 已等待审批 %.1f 小时，超时阈值为 %d 小时。紧急程度: %s",
                level,
                result.contractId,
                result.elapsedHours,
                result.timeoutHours,
                result.urgency
        );
    }

    public int getTimeoutHoursByUrgency(String urgency) {
        ContractConfig.Timeout timeoutConfig = contractConfig.getApproval().getTimeout();
        if (timeoutConfig == null) {
            return 48;
        }
        return timeoutConfig.getTimeoutByUrgency(urgency);
    }

    public enum TimeoutStatus {
        NORMAL,
        WARNING,
        TIMEOUT
    }

    public static class TimeoutCheckResult {
        public final String contractId;
        public final String approvalId;
        public final String approver;
        public final String urgency;
        public final double elapsedHours;
        public final int timeoutHours;
        public final TimeoutStatus status;

        public TimeoutCheckResult(String contractId, String approvalId, String approver,
                                   String urgency, double elapsedHours, int timeoutHours,
                                   TimeoutStatus status) {
            this.contractId = contractId;
            this.approvalId = approvalId;
            this.approver = approver;
            this.urgency = urgency;
            this.elapsedHours = elapsedHours;
            this.timeoutHours = timeoutHours;
            this.status = status;
        }
    }
}
