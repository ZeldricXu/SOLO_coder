package com.contractmgmt.scheduler;

import com.contractmgmt.entity.Contract;
import com.contractmgmt.service.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

@Component
public class ContractScheduler {

    private static final Logger logger = LoggerFactory.getLogger(ContractScheduler.class);

    private final ReminderService reminderService;
    private final ContractService contractService;
    private final ArchiveService archiveService;
    private final ApprovalTimeoutService approvalTimeoutService;
    private final DynamicExecutionCheckService dynamicExecutionCheckService;

    public ContractScheduler(
            ReminderService reminderService,
            ContractService contractService,
            ArchiveService archiveService,
            ApprovalTimeoutService approvalTimeoutService,
            DynamicExecutionCheckService dynamicExecutionCheckService) {
        this.reminderService = reminderService;
        this.contractService = contractService;
        this.archiveService = archiveService;
        this.approvalTimeoutService = approvalTimeoutService;
        this.dynamicExecutionCheckService = dynamicExecutionCheckService;
    }

    @Scheduled(cron = "0 0 8 * * ?")
    public void checkContractReminders() {
        logger.info("开始执行合同到期提醒检测...");
        try {
            reminderService.checkAndSendReminders();
            logger.info("合同到期提醒检测完成");
        } catch (Exception e) {
            logger.error("合同到期提醒检测失败", e);
        }
    }

    @Scheduled(cron = "0 0 */1 * * ?")
    public void checkApprovalTimeouts() {
        logger.info("开始执行审批超时检测...");
        try {
            List<ApprovalTimeoutService.TimeoutCheckResult> results =
                    approvalTimeoutService.checkApprovalTimeouts();

            long timeoutCount = results.stream()
                    .filter(r -> r.status == ApprovalTimeoutService.TimeoutStatus.TIMEOUT)
                    .count();
            long warningCount = results.stream()
                    .filter(r -> r.status == ApprovalTimeoutService.TimeoutStatus.WARNING)
                    .count();

            logger.info("审批超时检测完成，超时: {}, 警告: {}", timeoutCount, warningCount);
        } catch (Exception e) {
            logger.error("审批超时检测失败", e);
        }
    }

    @Scheduled(cron = "0 0 */2 * * ?")
    public void checkExecutionsDynamic() {
        logger.info("开始执行动态执行检查...");
        try {
            List<DynamicExecutionCheckService.CheckResult> results =
                    dynamicExecutionCheckService.checkExecutionsDynamic();

            long abnormalCount = results.stream()
                    .filter(r -> r.status != DynamicExecutionCheckService.CheckStatus.NORMAL)
                    .count();

            logger.info("动态执行检查完成，异常数: {}", abnormalCount);
        } catch (Exception e) {
            logger.error("动态执行检查失败", e);
        }
    }

    @Scheduled(cron = "0 0 0 * * ?")
    public void checkExpiredContracts() {
        logger.info("开始执行合同到期检测...");
        try {
            LocalDate today = LocalDate.now();
            List<Contract> expiredContracts = contractService.getExpiredContracts(today);

            for (Contract contract : expiredContracts) {
                logger.info("检测到已到期合同: {}, 到期日: {}",
                        contract.getContractId(), contract.getContractEnd());

                try {
                    archiveService.archiveContract(
                            contract.getContractId(),
                            "scheduler",
                            "合同到期自动归档"
                    );
                } catch (Exception e) {
                    logger.error("自动归档合同失败: {}", contract.getContractId(), e);
                }
            }

            logger.info("合同到期检测完成，处理了 {} 个到期合同", expiredContracts.size());
        } catch (Exception e) {
            logger.error("合同到期检测失败", e);
        }
    }

    @Scheduled(cron = "0 0 */6 * * ?")
    public void assessContractActivityLevels() {
        logger.info("开始评估合同活跃程度...");
        try {
            List<Contract> activeContracts = contractService.listContractsByStatus("approved");
            int updatedCount = 0;

            for (Contract contract : activeContracts) {
                if (!"completed".equals(contract.getExecutionStatus())) {
                    dynamicExecutionCheckService.assessAndUpdateActivityLevel(
                            contract.getContractId());
                    updatedCount++;
                }
            }

            logger.info("合同活跃程度评估完成，评估了 {} 个合同", updatedCount);
        } catch (Exception e) {
            logger.error("合同活跃程度评估失败", e);
        }
    }
}
