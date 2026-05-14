package com.contractmgmt.service;

import cn.hutool.core.util.IdUtil;
import com.contractmgmt.entity.ContractStat;
import com.contractmgmt.repository.ContractRepository;
import com.contractmgmt.repository.ContractStatRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class StatisticsService {

    private static final Logger logger = LoggerFactory.getLogger(StatisticsService.class);

    private final ContractStatRepository contractStatRepository;
    private final ContractRepository contractRepository;

    public StatisticsService(
            ContractStatRepository contractStatRepository,
            ContractRepository contractRepository) {
        this.contractStatRepository = contractStatRepository;
        this.contractRepository = contractRepository;
    }

    private ContractStat getOrCreateCurrentStat() {
        String currentMonth = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM"));
        Optional<ContractStat> existing = contractStatRepository.findByStatMonth(currentMonth);

        if (existing.isPresent()) {
            return existing.get();
        }

        ContractStat stat = new ContractStat();
        stat.setStatId("stat_" + IdUtil.getSnowflakeNextIdStr());
        stat.setStatMonth(currentMonth);
        stat.setTotalCount(0);
        stat.setActiveCount(0);
        stat.setArchivedCount(0);
        stat.setRejectedCount(0);
        stat.setPendingCount(0);
        stat.setTotalAmount(BigDecimal.ZERO);
        stat.setActiveAmount(BigDecimal.ZERO);
        return contractStatRepository.save(stat);
    }

    public void incrementTotalCount() {
        ContractStat stat = getOrCreateCurrentStat();
        stat.setTotalCount(stat.getTotalCount() + 1);
        contractStatRepository.save(stat);
        logger.debug("统计更新: 总合同数+1");
    }

    public void incrementPendingCount() {
        ContractStat stat = getOrCreateCurrentStat();
        stat.setPendingCount(stat.getPendingCount() + 1);
        contractStatRepository.save(stat);
        logger.debug("统计更新: 待审批数+1");
    }

    public void decrementPendingCount() {
        ContractStat stat = getOrCreateCurrentStat();
        if (stat.getPendingCount() > 0) {
            stat.setPendingCount(stat.getPendingCount() - 1);
            contractStatRepository.save(stat);
            logger.debug("统计更新: 待审批数-1");
        }
    }

    public void incrementActiveCount() {
        ContractStat stat = getOrCreateCurrentStat();
        stat.setActiveCount(stat.getActiveCount() + 1);
        contractStatRepository.save(stat);
        logger.debug("统计更新: 生效合同数+1");
    }

    public void decrementActiveCount() {
        ContractStat stat = getOrCreateCurrentStat();
        if (stat.getActiveCount() > 0) {
            stat.setActiveCount(stat.getActiveCount() - 1);
            contractStatRepository.save(stat);
            logger.debug("统计更新: 生效合同数-1");
        }
    }

    public void incrementRejectedCount() {
        ContractStat stat = getOrCreateCurrentStat();
        stat.setRejectedCount(stat.getRejectedCount() + 1);
        contractStatRepository.save(stat);
        logger.debug("统计更新: 已拒绝数+1");
    }

    public void incrementArchivedCount() {
        ContractStat stat = getOrCreateCurrentStat();
        stat.setArchivedCount(stat.getArchivedCount() + 1);
        contractStatRepository.save(stat);
        logger.debug("统计更新: 已归档数+1");
    }

    public void addActiveAmount(BigDecimal amount) {
        if (amount == null) return;
        ContractStat stat = getOrCreateCurrentStat();
        stat.setActiveAmount(stat.getActiveAmount().add(amount));
        stat.setTotalAmount(stat.getTotalAmount().add(amount));
        contractStatRepository.save(stat);
        logger.debug("统计更新: 生效金额+{}", amount);
    }

    public void subtractActiveAmount(BigDecimal amount) {
        if (amount == null) return;
        ContractStat stat = getOrCreateCurrentStat();
        BigDecimal newAmount = stat.getActiveAmount().subtract(amount);
        stat.setActiveAmount(newAmount.compareTo(BigDecimal.ZERO) > 0 ? newAmount : BigDecimal.ZERO);
        contractStatRepository.save(stat);
        logger.debug("统计更新: 生效金额-{}", amount);
    }

    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new HashMap<>();

        Long totalCount = contractRepository.count();
        Long activeCount = contractRepository.countByContractStatus("approved");
        Long pendingCount = contractRepository.countByContractStatus("pending_approval");
        Long rejectedCount = contractRepository.countByContractStatus("rejected");
        Long archivedCount = contractRepository.countByContractStatus("archived");

        BigDecimal totalAmount = contractRepository.sumAmountByContractStatus("approved");
        if (totalAmount == null) totalAmount = BigDecimal.ZERO;

        stats.put("total_count", totalCount);
        stats.put("active_count", activeCount);
        stats.put("pending_count", pendingCount);
        stats.put("rejected_count", rejectedCount);
        stats.put("archived_count", archivedCount);
        stats.put("total_amount", totalAmount);

        List<ContractStat> monthlyStats = contractStatRepository.findAllOrderByStatMonthDesc();
        if (monthlyStats.size() > 12) {
            monthlyStats = monthlyStats.subList(0, 12);
        }
        stats.put("monthly_stats", monthlyStats);

        return stats;
    }

    public ContractStat getMonthlyStat(String month) {
        return contractStatRepository.findByStatMonth(month).orElse(null);
    }
}
