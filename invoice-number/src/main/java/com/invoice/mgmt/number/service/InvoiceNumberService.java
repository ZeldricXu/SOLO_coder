package com.invoice.mgmt.number.service;

import com.invoice.mgmt.common.entity.InvoiceNumber;
import com.invoice.mgmt.common.exception.InvoiceException;
import com.invoice.mgmt.common.util.DateTimeUtil;
import com.invoice.mgmt.number.mapper.InvoiceNumberMapper;
import com.invoice.mgmt.statistics.service.InvoiceStatisticsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class InvoiceNumberService {
    private static final Logger logger = LoggerFactory.getLogger(InvoiceNumberService.class);

    @Value("${invoice.number.warning.base-threshold:20}")
    private int baseWarningThreshold;

    @Value("${invoice.number.warning.high-frequency:5}")
    private int highFrequencyThreshold;

    @Value("${invoice.number.warning.low-frequency:50}")
    private int lowFrequencyThreshold;

    @Value("${invoice.number.frequency.window-days:7}")
    private int frequencyWindowDays;

    @Value("${invoice.number.frequency.high-threshold:20}")
    private int highFrequencyDayThreshold;

    @Value("${invoice.number.frequency.low-threshold:2}")
    private int lowFrequencyDayThreshold;

    @Autowired
    private InvoiceNumberMapper invoiceNumberMapper;

    @Autowired
    private InvoiceStatisticsService statisticsService;

    private final ConcurrentHashMap<String, Long> lastWarningTimeMap = new ConcurrentHashMap<>();

    public static class FrequencyLevel {
        public static final int VERY_HIGH = 3;
        public static final int HIGH = 2;
        public static final int NORMAL = 1;
        public static final int LOW = 0;
    }

    @Transactional
    public InvoiceNumber create(String invoiceType, String invoiceCode, String startNo, String endNo) {
        int totalCount = calculateCount(startNo, endNo);
        if (totalCount <= 0) {
            throw new InvoiceException(400, "发票号码范围无效");
        }
        InvoiceNumber number = InvoiceNumber.builder()
                .invoiceType(invoiceType)
                .invoiceCode(invoiceCode)
                .startNo(startNo)
                .endNo(endNo)
                .currentNo(startNo)
                .totalCount(totalCount)
                .usedCount(0)
                .remainingCount(totalCount)
                .status("active")
                .createdAt(DateTimeUtil.now())
                .updatedAt(DateTimeUtil.now())
                .build();
        invoiceNumberMapper.insert(number);
        return number;
    }

    @Transactional
    public synchronized String allocate(String invoiceType) {
        InvoiceNumber available = invoiceNumberMapper.findFirstAvailable(invoiceType);
        if (available == null || available.getRemainingCount() <= 0) {
            logger.warn("发票号码不足, type: {}", invoiceType);
            throw InvoiceException.numberInsufficient();
        }
        String allocatedNo = available.getCurrentNo();
        int newUsed = available.getUsedCount() + 1;
        int newRemaining = available.getRemainingCount() - 1;
        String newCurrent = nextNumber(allocatedNo, available.getEndNo());

        int dynamicThreshold = calculateDynamicWarningThreshold(invoiceType);

        if (newRemaining <= dynamicThreshold) {
            triggerWarningIfNeeded(invoiceType, newRemaining, dynamicThreshold);
        }

        invoiceNumberMapper.updateUsedCount(available.getId(), newUsed, newRemaining, newCurrent);
        return allocatedNo;
    }

    public int calculateDynamicWarningThreshold(String invoiceType) {
        double avgDaily = statisticsService.getAverageDailyFrequency(invoiceType, frequencyWindowDays);
        int frequencyLevel = getFrequencyLevel(avgDaily);

        int threshold;
        switch (frequencyLevel) {
            case FrequencyLevel.VERY_HIGH:
                threshold = Math.max(5, (int) (avgDaily * 2));
                break;
            case FrequencyLevel.HIGH:
                threshold = Math.max(10, highFrequencyThreshold);
                break;
            case FrequencyLevel.LOW:
                threshold = Math.min(50, lowFrequencyThreshold);
                break;
            case FrequencyLevel.NORMAL:
            default:
                threshold = baseWarningThreshold;
                break;
        }

        logger.debug("动态预警阈值计算: type={}, avgDaily={:.2f}, level={}, threshold={}",
                invoiceType, avgDaily, frequencyLevel, threshold);
        return threshold;
    }

    public int getFrequencyLevel(double avgDaily) {
        if (avgDaily >= highFrequencyDayThreshold) {
            return FrequencyLevel.VERY_HIGH;
        } else if (avgDaily >= highFrequencyDayThreshold / 2.0) {
            return FrequencyLevel.HIGH;
        } else if (avgDaily <= lowFrequencyDayThreshold) {
            return FrequencyLevel.LOW;
        } else {
            return FrequencyLevel.NORMAL;
        }
    }

    public String getFrequencyLevelName(int level) {
        switch (level) {
            case FrequencyLevel.VERY_HIGH:
                return "极高频率";
            case FrequencyLevel.HIGH:
                return "高频";
            case FrequencyLevel.LOW:
                return "低频";
            case FrequencyLevel.NORMAL:
            default:
                return "正常频率";
        }
    }

    private void triggerWarningIfNeeded(String invoiceType, int remaining, int threshold) {
        long now = System.currentTimeMillis();
        Long lastWarning = lastWarningTimeMap.get(invoiceType);

        long warningInterval = getWarningInterval(invoiceType);

        if (lastWarning == null || (now - lastWarning) >= warningInterval) {
            lastWarningTimeMap.put(invoiceType, now);

            double avgDaily = statisticsService.getAverageDailyFrequency(invoiceType, frequencyWindowDays);
            int estimatedDays = avgDaily > 0 ? (int) (remaining / avgDaily) : Integer.MAX_VALUE;

            logger.warn("发票号码预警, type={}, remaining={}, threshold={}, 日均开票={:.2f}, 预计可用天数={}",
                    invoiceType, remaining, threshold, avgDaily, estimatedDays);
        }
    }

    private long getWarningInterval(String invoiceType) {
        double avgDaily = statisticsService.getAverageDailyFrequency(invoiceType, frequencyWindowDays);
        int level = getFrequencyLevel(avgDaily);

        switch (level) {
            case FrequencyLevel.VERY_HIGH:
                return 30 * 60 * 1000;
            case FrequencyLevel.HIGH:
                return 60 * 60 * 1000;
            case FrequencyLevel.LOW:
                return 6 * 60 * 60 * 1000;
            case FrequencyLevel.NORMAL:
            default:
                return 2 * 60 * 60 * 1000;
        }
    }

    public int getBaseWarningThreshold() {
        return baseWarningThreshold;
    }

    public int getHighFrequencyThreshold() {
        return highFrequencyThreshold;
    }

    public int getLowFrequencyThreshold() {
        return lowFrequencyThreshold;
    }

    public int getFrequencyWindowDays() {
        return frequencyWindowDays;
    }

    public List<InvoiceNumber> listByType(String invoiceType) {
        return invoiceNumberMapper.findByType(invoiceType);
    }

    public List<InvoiceNumber> listAvailable(String invoiceType) {
        return invoiceNumberMapper.findAvailableByType(invoiceType);
    }

    public int getRemainingCount(String invoiceType) {
        return invoiceNumberMapper.findAvailableByType(invoiceType).stream()
                .mapToInt(InvoiceNumber::getRemainingCount)
                .sum();
    }

    public String getInvoiceCode(String invoiceType) {
        List<InvoiceNumber> list = invoiceNumberMapper.findAvailableByType(invoiceType);
        if (list.isEmpty()) {
            throw InvoiceException.numberInsufficient();
        }
        return list.get(0).getInvoiceCode();
    }

    private int calculateCount(String start, String end) {
        try {
            long s = Long.parseLong(start);
            long e = Long.parseLong(end);
            return (int) (e - s + 1);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private String nextNumber(String current, String endNo) {
        try {
            long curr = Long.parseLong(current);
            long end = Long.parseLong(endNo);
            if (curr >= end) return null;
            String format = "%0" + current.length() + "d";
            return String.format(format, curr + 1);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
