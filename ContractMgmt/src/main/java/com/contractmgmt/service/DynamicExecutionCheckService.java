package com.contractmgmt.service;

import com.contractmgmt.config.ContractConfig;
import com.contractmgmt.entity.Contract;
import com.contractmgmt.entity.ExecutionRecord;
import com.contractmgmt.repository.ContractRepository;
import com.contractmgmt.repository.ExecutionRecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class DynamicExecutionCheckService {

    private static final Logger logger = LoggerFactory.getLogger(DynamicExecutionCheckService.class);

    private final ContractRepository contractRepository;
    private final ExecutionRecordRepository executionRecordRepository;
    private final ContractConfig contractConfig;
    private final ConcurrentHashMap<String, LocalDateTime> lastCheckTimeMap = new ConcurrentHashMap<>();

    public DynamicExecutionCheckService(
            ContractRepository contractRepository,
            ExecutionRecordRepository executionRecordRepository,
            ContractConfig contractConfig) {
        this.contractRepository = contractRepository;
        this.executionRecordRepository = executionRecordRepository;
        this.contractConfig = contractConfig;
    }

    @Transactional
    public List<CheckResult> checkExecutionsDynamic() {
        List<CheckResult> results = new ArrayList<>();

        List<Contract> activeContracts = contractRepository.findByContractStatusIn(
                List.of("approved"));

        logger.info("开始动态执行检查，共 {} 个已生效合同", activeContracts.size());

        for (Contract contract : activeContracts) {
            if ("completed".equals(contract.getExecutionStatus())) {
                continue;
            }

            if (!shouldCheckContract(contract)) {
                logger.debug("跳过检查合同 {}：未到检查时间", contract.getContractId());
                continue;
            }

            CheckResult result = checkSingleContract(contract);
            results.add(result);

            updateLastCheckTime(contract.getContractId());

            if (result.status != CheckStatus.NORMAL) {
                handleExecutionIssue(result, contract);
            }
        }

        logger.info("动态执行检查完成，检查了 {} 个合同，发现 {} 个异常",
                results.size(),
                results.stream().filter(r -> r.status != CheckStatus.NORMAL).count());

        return results;
    }

    private boolean shouldCheckContract(Contract contract) {
        String activityLevel = contract.getActivityLevel() != null ? contract.getActivityLevel() : "normal";
        ContractConfig.ExecutionCheck checkConfig = contractConfig.getApproval().getExecutionCheck();
        if (checkConfig == null) {
            return true;
        }

        int intervalHours = checkConfig.getCheckIntervalByActivity(activityLevel);
        LocalDateTime lastCheck = lastCheckTimeMap.get(contract.getContractId());

        if (lastCheck == null) {
            return true;
        }

        long hoursSinceLastCheck = ChronoUnit.HOURS.between(lastCheck, LocalDateTime.now());
        return hoursSinceLastCheck >= intervalHours;
    }

    private CheckResult checkSingleContract(Contract contract) {
        List<ExecutionRecord> records = executionRecordRepository
                .findByContractIdOrderByExecutionTimeDesc(contract.getContractId());

        Integer currentProgress = contract.getExecutionProgress();
        if (currentProgress == null) {
            currentProgress = 0;
        }

        LocalDate contractEnd = contract.getContractEnd();
        LocalDate today = LocalDate.now();
        LocalDate contractStart = contract.getContractStart();

        double expectedProgress = calculateExpectedProgress(contractStart, contractEnd, today);
        double progressGap = expectedProgress - currentProgress;

        LocalDateTime lastExecutionTime = null;
        long hoursSinceLastExecution = 0;

        if (!records.isEmpty()) {
            lastExecutionTime = records.get(0).getExecutionTime();
            hoursSinceLastExecution = ChronoUnit.HOURS.between(lastExecutionTime, LocalDateTime.now());
        } else {
            if (contract.getEffectiveTime() != null) {
                hoursSinceLastExecution = ChronoUnit.HOURS.between(
                        contract.getEffectiveTime(), LocalDateTime.now());
            }
        }

        CheckStatus status = determineCheckStatus(
                currentProgress, progressGap, hoursSinceLastExecution, contract.getActivityLevel());

        String issueDescription = generateIssueDescription(status, progressGap, hoursSinceLastExecution);

        return new CheckResult(
                contract.getContractId(),
                currentProgress,
                expectedProgress,
                progressGap,
                hoursSinceLastExecution,
                contract.getActivityLevel(),
                status,
                issueDescription
        );
    }

    private double calculateExpectedProgress(LocalDate contractStart, LocalDate contractEnd, LocalDate today) {
        if (contractStart == null || contractEnd == null || contractStart.isAfter(contractEnd)) {
            return 0.0;
        }

        long totalDays = ChronoUnit.DAYS.between(contractStart, contractEnd);
        long elapsedDays = ChronoUnit.DAYS.between(contractStart, today);

        if (totalDays <= 0) {
            return today.isAfter(contractEnd) ? 100.0 : 0.0;
        }

        return Math.min(100.0, Math.max(0.0, (elapsedDays * 100.0) / totalDays));
    }

    private CheckStatus determineCheckStatus(
            Integer currentProgress, double progressGap, long hoursSinceLastExecution, String activityLevel) {

        String effectiveLevel = activityLevel != null ? activityLevel : "normal";
        ContractConfig.ExecutionCheck checkConfig = contractConfig.getApproval().getExecutionCheck();

        long maxIdleHours;
        if (checkConfig != null) {
            int checkInterval = checkConfig.getCheckIntervalByActivity(effectiveLevel);
            maxIdleHours = checkInterval * 2L;
        } else {
            maxIdleHours = 48L;
        }

        if (currentProgress >= 100) {
            return CheckStatus.AUTO_COMPLETE;
        }

        if (progressGap > 25.0) {
            return CheckStatus.SEVERE_DELAY;
        }

        if (progressGap > 15.0) {
            return CheckStatus.DELAYED;
        }

        if (hoursSinceLastExecution > maxIdleHours && currentProgress < 100) {
            return CheckStatus.STALLED;
        }

        if (progressGap > 5.0) {
            return CheckStatus.MINOR_DELAY;
        }

        return CheckStatus.NORMAL;
    }

    private String generateIssueDescription(CheckStatus status, double progressGap, long hoursSinceLastExecution) {
        switch (status) {
            case AUTO_COMPLETE:
                return "执行进度已达100%，但状态未更新为已完成";
            case SEVERE_DELAY:
                return String.format("执行进度严重落后预期 %.1f%%", progressGap);
            case DELAYED:
                return String.format("执行进度落后预期 %.1f%%", progressGap);
            case STALLED:
                return String.format("已 %d 小时未更新执行记录", hoursSinceLastExecution);
            case MINOR_DELAY:
                return String.format("执行进度略微落后预期 %.1f%%", progressGap);
            case NORMAL:
            default:
                return "";
        }
    }

    private void handleExecutionIssue(CheckResult result, Contract contract) {
        switch (result.status) {
            case AUTO_COMPLETE:
                autoCompleteExecution(contract);
                break;
            case SEVERE_DELAY:
            case DELAYED:
            case STALLED:
            case MINOR_DELAY:
                logger.warn("合同执行异常: 合同={}, 活跃程度={}, 状态={}, 描述={}",
                        result.contractId, result.activityLevel, result.status, result.issueDescription);
                break;
            default:
                break;
        }
    }

    private void autoCompleteExecution(Contract contract) {
        if (contract.getExecutionProgress() != null &&
                contract.getExecutionProgress() >= 100 &&
                !"completed".equals(contract.getExecutionStatus())) {

            contract.setExecutionStatus("completed");
            contract.setUpdatedAt(LocalDateTime.now());
            contractRepository.save(contract);

            logger.info("动态执行检查：自动完成合同执行: {}", contract.getContractId());
        }
    }

    private void updateLastCheckTime(String contractId) {
        lastCheckTimeMap.put(contractId, LocalDateTime.now());
    }

    public int getCheckIntervalByActivity(String activityLevel) {
        ContractConfig.ExecutionCheck checkConfig = contractConfig.getApproval().getExecutionCheck();
        if (checkConfig == null) {
            return 6;
        }
        return checkConfig.getCheckIntervalByActivity(activityLevel);
    }

    public void updateContractActivityLevel(String contractId, String newLevel) {
        Contract contract = contractRepository.findByContractId(contractId).orElse(null);
        if (contract != null) {
            contract.setActivityLevel(newLevel);
            contract.setUpdatedAt(LocalDateTime.now());
            contractRepository.save(contract);
            logger.info("更新合同活跃程度: {} -> {}", contractId, newLevel);
        }
    }

    public void assessAndUpdateActivityLevel(String contractId) {
        List<ExecutionRecord> records = executionRecordRepository
                .findByContractIdOrderByExecutionTimeDesc(contractId);

        String newLevel = "normal";

        if (records.isEmpty()) {
            Contract contract = contractRepository.findByContractId(contractId).orElse(null);
            if (contract != null && contract.getEffectiveTime() != null) {
                long daysSinceEffective = ChronoUnit.DAYS.between(
                        contract.getEffectiveTime().toLocalDate(), LocalDate.now());
                if (daysSinceEffective > 30) {
                    newLevel = "inactive";
                }
            }
        } else {
            ExecutionRecord latest = records.get(0);
            long daysSinceLastUpdate = ChronoUnit.DAYS.between(
                    latest.getExecutionTime().toLocalDate(), LocalDate.now());

            if (daysSinceLastUpdate <= 1) {
                newLevel = "active";
            } else if (daysSinceLastUpdate > 14) {
                newLevel = "inactive";
            }
        }

        updateContractActivityLevel(contractId, newLevel);
    }

    public enum CheckStatus {
        NORMAL,
        MINOR_DELAY,
        DELAYED,
        SEVERE_DELAY,
        STALLED,
        AUTO_COMPLETE
    }

    public static class CheckResult {
        public final String contractId;
        public final int currentProgress;
        public final double expectedProgress;
        public final double progressGap;
        public final long hoursSinceLastExecution;
        public final String activityLevel;
        public final CheckStatus status;
        public final String issueDescription;

        public CheckResult(String contractId, int currentProgress, double expectedProgress,
                           double progressGap, long hoursSinceLastExecution, String activityLevel,
                           CheckStatus status, String issueDescription) {
            this.contractId = contractId;
            this.currentProgress = currentProgress;
            this.expectedProgress = expectedProgress;
            this.progressGap = progressGap;
            this.hoursSinceLastExecution = hoursSinceLastExecution;
            this.activityLevel = activityLevel;
            this.status = status;
            this.issueDescription = issueDescription;
        }
    }
}
