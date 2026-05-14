package com.contractmgmt.service;

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
import java.util.List;

@Service
public class ExecutionCheckService {

    private static final Logger logger = LoggerFactory.getLogger(ExecutionCheckService.class);

    private static final int MAX_DAYS_WITHOUT_UPDATE = 7;
    private static final double PROGRESS_THRESHOLD = 100.0;

    private final ContractRepository contractRepository;
    private final ExecutionRecordRepository executionRecordRepository;

    public ExecutionCheckService(
            ContractRepository contractRepository,
            ExecutionRecordRepository executionRecordRepository) {
        this.contractRepository = contractRepository;
        this.executionRecordRepository = executionRecordRepository;
    }

    @Transactional
    public List<ExecutionCheckResult> checkExecutions() {
        List<ExecutionCheckResult> results = new ArrayList<>();

        List<Contract> activeContracts = contractRepository.findByContractStatusIn(
                List.of("approved"));

        logger.info("开始检查 {} 个已生效合同的执行情况", activeContracts.size());

        for (Contract contract : activeContracts) {
            if ("completed".equals(contract.getExecutionStatus())) {
                continue;
            }

            ExecutionCheckResult result = checkSingleContract(contract);
            results.add(result);

            if (result.status != ExecutionStatus.NORMAL) {
                handleExecutionIssue(result, contract);
            }
        }

        logger.info("执行检查完成，共发现 {} 个异常执行",
                results.stream().filter(r -> r.status != ExecutionStatus.NORMAL).count());

        return results;
    }

    private ExecutionCheckResult checkSingleContract(Contract contract) {
        List<ExecutionRecord> records = executionRecordRepository
                .findByContractIdOrderByExecutionTimeDesc(contract.getContractId());

        Integer currentProgress = contract.getExecutionProgress();
        if (currentProgress == null) {
            currentProgress = 0;
        }

        LocalDate contractEnd = contract.getContractEnd();
        LocalDate today = LocalDate.now();
        LocalDate contractStart = contract.getContractStart();

        double expectedProgress = 0.0;
        if (contractStart != null && contractEnd != null && !contractStart.isAfter(contractEnd)) {
            long totalDays = ChronoUnit.DAYS.between(contractStart, contractEnd);
            long elapsedDays = ChronoUnit.DAYS.between(contractStart, today);
            if (totalDays > 0 && elapsedDays > 0) {
                expectedProgress = Math.min(100.0, (elapsedDays * 100.0) / totalDays);
            }
        }

        double progressGap = expectedProgress - currentProgress;

        LocalDateTime lastExecutionTime = null;
        long daysSinceLastUpdate = 0;

        if (!records.isEmpty()) {
            lastExecutionTime = records.get(0).getExecutionTime();
            daysSinceLastUpdate = ChronoUnit.DAYS.between(lastExecutionTime.toLocalDate(), today);
        } else {
            if (contract.getEffectiveTime() != null) {
                daysSinceLastUpdate = ChronoUnit.DAYS.between(
                        contract.getEffectiveTime().toLocalDate(), today);
            }
        }

        ExecutionStatus status = ExecutionStatus.NORMAL;
        String issueDescription = "";

        if (currentProgress >= PROGRESS_THRESHOLD && !"completed".equals(contract.getExecutionStatus())) {
            status = ExecutionStatus.AUTO_COMPLETE;
            issueDescription = "执行进度已达100%，但状态未更新为已完成";
        } else if (progressGap > 20.0) {
            status = ExecutionStatus.DELAYED;
            issueDescription = String.format("执行进度落后预期 %.1f%%", progressGap);
        } else if (daysSinceLastUpdate > MAX_DAYS_WITHOUT_UPDATE && currentProgress < 100) {
            status = ExecutionStatus.STALLED;
            issueDescription = String.format("已 %d 天未更新执行记录", daysSinceLastUpdate);
        }

        return new ExecutionCheckResult(
                contract.getContractId(),
                currentProgress,
                expectedProgress,
                progressGap,
                daysSinceLastUpdate,
                status,
                issueDescription
        );
    }

    private void handleExecutionIssue(ExecutionCheckResult result, Contract contract) {
        switch (result.status) {
            case AUTO_COMPLETE:
                autoCompleteExecution(contract);
                break;
            case DELAYED:
            case STALLED:
                logger.warn("合同执行异常: 合同={}, 状态={}, 描述={}",
                        result.contractId, result.status, result.issueDescription);
                break;
            default:
                break;
        }
    }

    @Transactional
    public void autoCompleteExecution(Contract contract) {
        if (contract.getExecutionProgress() != null &&
                contract.getExecutionProgress() >= 100 &&
                !"completed".equals(contract.getExecutionStatus())) {

            contract.setExecutionStatus("completed");
            contract.setUpdatedAt(LocalDateTime.now());
            contractRepository.save(contract);

            logger.info("自动完成合同执行: {}", contract.getContractId());
        }
    }

    public boolean checkProgressAccuracy(Integer reportedProgress, String contractId) {
        if (reportedProgress == null || reportedProgress < 0 || reportedProgress > 100) {
            return false;
        }

        List<ExecutionRecord> records = executionRecordRepository
                .findByContractIdOrderByExecutionTimeDesc(contractId);

        if (records.isEmpty()) {
            return reportedProgress >= 0 && reportedProgress <= 100;
        }

        Integer maxProgress = executionRecordRepository.findMaxProgressByContractId(contractId);
        if (maxProgress != null && reportedProgress < maxProgress) {
            return false;
        }

        return true;
    }

    public List<ExecutionMissingItem> findMissingExecutions() {
        List<ExecutionMissingItem> missingItems = new ArrayList<>();

        List<Contract> activeContracts = contractRepository.findByContractStatusIn(
                List.of("approved"));

        for (Contract contract : activeContracts) {
            if ("completed".equals(contract.getExecutionStatus())) {
                continue;
            }

            List<ExecutionRecord> records = executionRecordRepository
                    .findByContractIdOrderByExecutionTimeDesc(contract.getContractId());

            if (records.isEmpty()) {
                missingItems.add(new ExecutionMissingItem(
                        contract.getContractId(),
                        "从未执行",
                        0,
                        contract.getEffectiveTime()
                ));
            } else {
                LocalDateTime lastTime = records.get(0).getExecutionTime();
                long days = ChronoUnit.DAYS.between(lastTime.toLocalDate(), LocalDate.now());

                if (days > MAX_DAYS_WITHOUT_UPDATE) {
                    missingItems.add(new ExecutionMissingItem(
                            contract.getContractId(),
                            "执行记录缺失",
                            days,
                            lastTime
                    ));
                }
            }
        }

        return missingItems;
    }

    public enum ExecutionStatus {
        NORMAL,
        DELAYED,
        STALLED,
        AUTO_COMPLETE
    }

    public static class ExecutionCheckResult {
        public final String contractId;
        public final int currentProgress;
        public final double expectedProgress;
        public final double progressGap;
        public final long daysSinceLastUpdate;
        public final ExecutionStatus status;
        public final String issueDescription;

        public ExecutionCheckResult(String contractId, int currentProgress, double expectedProgress,
                                     double progressGap, long daysSinceLastUpdate,
                                     ExecutionStatus status, String issueDescription) {
            this.contractId = contractId;
            this.currentProgress = currentProgress;
            this.expectedProgress = expectedProgress;
            this.progressGap = progressGap;
            this.daysSinceLastUpdate = daysSinceLastUpdate;
            this.status = status;
            this.issueDescription = issueDescription;
        }
    }

    public static class ExecutionMissingItem {
        public final String contractId;
        public final String issueType;
        public final long daysSinceLastExecution;
        public final LocalDateTime lastExecutionTime;

        public ExecutionMissingItem(String contractId, String issueType,
                                     long daysSinceLastExecution, LocalDateTime lastExecutionTime) {
            this.contractId = contractId;
            this.issueType = issueType;
            this.daysSinceLastExecution = daysSinceLastExecution;
            this.lastExecutionTime = lastExecutionTime;
        }
    }
}
