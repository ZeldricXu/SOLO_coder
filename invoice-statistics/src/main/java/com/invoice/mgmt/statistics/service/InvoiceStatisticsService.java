package com.invoice.mgmt.statistics.service;

import com.invoice.mgmt.common.dto.InvoiceStatisticsDTO;
import com.invoice.mgmt.common.entity.InvoiceStatistics;
import com.invoice.mgmt.common.entity.InvoiceTypeStatistics;
import com.invoice.mgmt.common.util.DateTimeUtil;
import com.invoice.mgmt.common.util.IdGenerator;
import com.invoice.mgmt.statistics.mapper.InvoiceStatisticsMapper;
import com.invoice.mgmt.statistics.mapper.InvoiceTypeStatisticsMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
public class InvoiceStatisticsService {
    private static final Logger logger = LoggerFactory.getLogger(InvoiceStatisticsService.class);
    private static final DateTimeFormatter DAY_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final int DEFAULT_FREQUENCY_WINDOW_DAYS = 7;

    @Autowired
    private InvoiceStatisticsMapper statisticsMapper;

    @Autowired
    private InvoiceTypeStatisticsMapper typeStatisticsMapper;

    @Transactional
    public void recordIssue(BigDecimal amount, BigDecimal tax) {
        String month = DateTimeUtil.getCurrentMonth();
        InvoiceStatistics stat = statisticsMapper.findByMonth(month);
        if (stat == null) {
            stat = InvoiceStatistics.builder()
                    .statId(IdGenerator.generateStatId())
                    .statMonth(month)
                    .issueCount(1)
                    .totalAmount(amount)
                    .totalTax(tax)
                    .verifyCount(0)
                    .verifyPassCount(0)
                    .reimburseCount(0)
                    .reimburseApproveCount(0)
                    .createdAt(DateTimeUtil.now())
                    .updatedAt(DateTimeUtil.now())
                    .build();
            statisticsMapper.insert(stat);
        } else {
            statisticsMapper.incrementIssueCount(month, amount, tax);
        }
        logger.info("记录开票统计: month={}, amount={}", month, amount);
    }

    @Transactional
    public void recordIssueByType(String invoiceType, BigDecimal amount, BigDecimal tax) {
        String day = LocalDate.now().format(DAY_FORMAT);
        InvoiceTypeStatistics typeStat = typeStatisticsMapper.findByDayAndType(day, invoiceType);
        if (typeStat == null) {
            typeStat = InvoiceTypeStatistics.builder()
                    .statId(IdGenerator.generate("typestat_"))
                    .statDay(day)
                    .invoiceType(invoiceType)
                    .issueCount(1)
                    .totalAmount(amount)
                    .totalTax(tax)
                    .createdAt(DateTimeUtil.now())
                    .updatedAt(DateTimeUtil.now())
                    .build();
            typeStatisticsMapper.insert(typeStat);
        } else {
            typeStatisticsMapper.incrementIssueCount(day, invoiceType, amount, tax);
        }
        logger.info("记录按类型开票统计: type={}, day={}, amount={}", invoiceType, day, amount);
    }

    public int getIssueFrequency(String invoiceType) {
        return getIssueFrequency(invoiceType, DEFAULT_FREQUENCY_WINDOW_DAYS);
    }

    public int getIssueFrequency(String invoiceType, int days) {
        Integer count = typeStatisticsMapper.getIssueCountByTypeAndDays(invoiceType, days);
        return count != null ? count : 0;
    }

    public double getAverageDailyFrequency(String invoiceType) {
        return getAverageDailyFrequency(invoiceType, DEFAULT_FREQUENCY_WINDOW_DAYS);
    }

    public double getAverageDailyFrequency(String invoiceType, int days) {
        int count = getIssueFrequency(invoiceType, days);
        return (double) count / days;
    }

    @Transactional
    public void recordVerify(boolean passed) {
        String month = DateTimeUtil.getCurrentMonth();
        ensureMonthExists(month);
        statisticsMapper.incrementVerifyCount(month, passed);
        logger.info("记录验证统计: month={}, passed={}", month, passed);
    }

    @Transactional
    public void recordReimburse(boolean approved) {
        String month = DateTimeUtil.getCurrentMonth();
        ensureMonthExists(month);
        statisticsMapper.incrementReimburseCount(month, approved);
        logger.info("记录报销统计: month={}, approved={}", month, approved);
    }

    public InvoiceStatisticsDTO getCurrentMonth() {
        String month = DateTimeUtil.getCurrentMonth();
        InvoiceStatistics stat = statisticsMapper.findByMonth(month);
        return convertToDTO(stat);
    }

    public InvoiceStatisticsDTO getByMonth(String month) {
        InvoiceStatistics stat = statisticsMapper.findByMonth(month);
        return convertToDTO(stat);
    }

    public List<InvoiceStatistics> getByRange(String startMonth, String endMonth) {
        return statisticsMapper.findByMonthRange(startMonth, endMonth);
    }

    private void ensureMonthExists(String month) {
        InvoiceStatistics stat = statisticsMapper.findByMonth(month);
        if (stat == null) {
            stat = InvoiceStatistics.builder()
                    .statId(IdGenerator.generateStatId())
                    .statMonth(month)
                    .issueCount(0)
                    .totalAmount(BigDecimal.ZERO)
                    .totalTax(BigDecimal.ZERO)
                    .verifyCount(0)
                    .verifyPassCount(0)
                    .reimburseCount(0)
                    .reimburseApproveCount(0)
                    .createdAt(DateTimeUtil.now())
                    .updatedAt(DateTimeUtil.now())
                    .build();
            statisticsMapper.insert(stat);
        }
    }

    private InvoiceStatisticsDTO convertToDTO(InvoiceStatistics stat) {
        if (stat == null) {
            return InvoiceStatisticsDTO.builder()
                    .statMonth(DateTimeUtil.getCurrentMonth())
                    .issueCount(0)
                    .totalAmount(BigDecimal.ZERO)
                    .totalTax(BigDecimal.ZERO)
                    .verifyCount(0)
                    .verifyPassCount(0)
                    .reimburseCount(0)
                    .reimburseApproveCount(0)
                    .build();
        }
        return InvoiceStatisticsDTO.builder()
                .statMonth(stat.getStatMonth())
                .issueCount(stat.getIssueCount())
                .totalAmount(stat.getTotalAmount())
                .totalTax(stat.getTotalTax())
                .verifyCount(stat.getVerifyCount())
                .verifyPassCount(stat.getVerifyPassCount())
                .reimburseCount(stat.getReimburseCount())
                .reimburseApproveCount(stat.getReimburseApproveCount())
                .build();
    }
}
