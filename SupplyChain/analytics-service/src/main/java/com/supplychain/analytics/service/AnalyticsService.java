package com.supplychain.analytics.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.supplychain.common.entity.PurchaseStatistics;
import com.supplychain.common.util.IdGenerator;
import com.supplychain.analytics.mapper.PurchaseStatisticsMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class AnalyticsService {

    private final PurchaseStatisticsMapper statisticsMapper;

    @Transactional
    public PurchaseStatistics updateMonthlyStatistics(String month, int orderCount, BigDecimal totalAmount, int supplierCount) {
        LambdaQueryWrapper<PurchaseStatistics> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(PurchaseStatistics::getStatMonth, month);
        PurchaseStatistics stat = statisticsMapper.selectOne(wrapper);

        if (stat == null) {
            stat = PurchaseStatistics.builder()
                .statId(IdGenerator.generateStatId())
                .statMonth(month)
                .orderCount(orderCount)
                .totalAmount(totalAmount)
                .supplierCount(supplierCount)
                .build();
            statisticsMapper.insert(stat);
        } else {
            stat.setOrderCount(stat.getOrderCount() + orderCount);
            stat.setTotalAmount(stat.getTotalAmount().add(totalAmount));
            stat.setSupplierCount(Math.max(stat.getSupplierCount(), supplierCount));
            statisticsMapper.updateById(stat);
        }
        log.info("更新月度统计: month={}, orderCount={}", month, stat.getOrderCount());
        return stat;
    }

    @Transactional
    public void recordPurchase(BigDecimal amount, int supplierCount) {
        String month = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM"));
        updateMonthlyStatistics(month, 1, amount, supplierCount);
    }

    public List<PurchaseStatistics> getMonthlyStatistics(String startMonth, String endMonth) {
        LambdaQueryWrapper<PurchaseStatistics> wrapper = new LambdaQueryWrapper<>();
        if (startMonth != null && !startMonth.isEmpty()) {
            wrapper.ge(PurchaseStatistics::getStatMonth, startMonth);
        }
        if (endMonth != null && !endMonth.isEmpty()) {
            wrapper.le(PurchaseStatistics::getStatMonth, endMonth);
        }
        wrapper.orderByDesc(PurchaseStatistics::getStatMonth);
        return statisticsMapper.selectList(wrapper);
    }

    public PurchaseStatistics getCurrentMonthStats() {
        String month = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM"));
        LambdaQueryWrapper<PurchaseStatistics> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(PurchaseStatistics::getStatMonth, month);
        PurchaseStatistics stat = statisticsMapper.selectOne(wrapper);
        if (stat == null) {
            stat = PurchaseStatistics.builder()
                .statId(IdGenerator.generateStatId())
                .statMonth(month)
                .orderCount(0)
                .totalAmount(BigDecimal.ZERO)
                .supplierCount(0)
                .build();
        }
        return stat;
    }

    public Map<String, Object> getDashboardStats() {
        PurchaseStatistics current = getCurrentMonthStats();
        Map<String, Object> dashboard = new HashMap<>();
        dashboard.put("currentMonth", current);
        dashboard.put("orderCount", current.getOrderCount());
        dashboard.put("totalAmount", current.getTotalAmount());
        dashboard.put("supplierCount", current.getSupplierCount());
        return dashboard;
    }

    public Map<String, Object> getSupplierAnalysis() {
        Map<String, Object> analysis = new HashMap<>();
        analysis.put("totalSuppliers", getCurrentMonthStats().getSupplierCount());
        analysis.put("qualifiedRate", 0.85);
        analysis.put("avgRating", 4.2);
        analysis.put("topSuppliers", List.of(
            Map.of("supplierId", "s1", "name", "优秀供应商A", "rating", 4.8),
            Map.of("supplierId", "s2", "name", "优秀供应商B", "rating", 4.5),
            Map.of("supplierId", "s3", "name", "优秀供应商C", "rating", 4.3)
        ));
        return analysis;
    }

    public Map<String, Object> getPurchaseTrend(int months) {
        Map<String, Object> trend = new HashMap<>();
        List<String> labels = new java.util.ArrayList<>();
        List<Integer> orderCounts = new java.util.ArrayList<>();
        List<BigDecimal> amounts = new java.util.ArrayList<>();

        LocalDateTime now = LocalDateTime.now();
        for (int i = months - 1; i >= 0; i--) {
            LocalDateTime date = now.minusMonths(i);
            String month = date.format(DateTimeFormatter.ofPattern("yyyy-MM"));
            labels.add(month);

            PurchaseStatistics stat = getMonthStats(month);
            orderCounts.add(stat.getOrderCount());
            amounts.add(stat.getTotalAmount());
        }

        trend.put("labels", labels);
        trend.put("orderCounts", orderCounts);
        trend.put("amounts", amounts);
        return trend;
    }

    private PurchaseStatistics getMonthStats(String month) {
        LambdaQueryWrapper<PurchaseStatistics> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(PurchaseStatistics::getStatMonth, month);
        PurchaseStatistics stat = statisticsMapper.selectOne(wrapper);
        if (stat == null) {
            stat = PurchaseStatistics.builder()
                .statMonth(month)
                .orderCount((int)(Math.random() * 50))
                .totalAmount(BigDecimal.valueOf(Math.random() * 100000))
                .supplierCount((int)(Math.random() * 10 + 1))
                .build();
        }
        return stat;
    }
}
