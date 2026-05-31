package com.orchestration.billing.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.orchestration.common.exception.BusinessException;
import com.orchestration.common.util.JsonUtil;
import com.orchestration.billing.service.BillingService;
import com.orchestration.persistence.entity.BillingCycle;
import com.orchestration.persistence.entity.BillingItem;
import com.orchestration.persistence.entity.PricingRule;
import com.orchestration.persistence.entity.UsageRecord;
import com.orchestration.persistence.mapper.BillingCycleMapper;
import com.orchestration.persistence.mapper.BillingItemMapper;
import com.orchestration.persistence.mapper.PricingRuleMapper;
import com.orchestration.persistence.mapper.UsageRecordMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class BillingServiceImpl implements BillingService {

    private final UsageRecordMapper usageRecordMapper;
    private final PricingRuleMapper pricingRuleMapper;
    private final BillingCycleMapper billingCycleMapper;
    private final BillingItemMapper billingItemMapper;

    @Override
    public Long recordUsage(Long tenantId, String resourceType, Long usageAmount, String unit, Map<String, String> tags) {
        UsageRecord record = new UsageRecord();
        record.setTenantId(tenantId);
        record.setResourceType(resourceType);
        record.setUsageAmount(usageAmount);
        record.setUnit(unit);
        record.setStartTime(LocalDateTime.now().minusMinutes(5));
        record.setEndTime(LocalDateTime.now());
        record.setTagsJson(tags != null ? JsonUtil.toJson(tags) : null);
        usageRecordMapper.insert(record);
        return record.getId();
    }

    @Override
    public List<UsageRecord> listUsageRecords(Long tenantId, String resourceType, Long startTime, Long endTime) {
        LocalDateTime start = LocalDateTime.ofInstant(new Date(startTime).toInstant(), java.time.ZoneId.systemDefault());
        LocalDateTime end = LocalDateTime.ofInstant(new Date(endTime).toInstant(), java.time.ZoneId.systemDefault());

        return usageRecordMapper.selectList(
                new LambdaQueryWrapper<UsageRecord>()
                        .eq(UsageRecord::getTenantId, tenantId)
                        .eq(resourceType != null, UsageRecord::getResourceType, resourceType)
                        .ge(UsageRecord::getStartTime, start)
                        .le(UsageRecord::getEndTime, end)
                        .orderByDesc(UsageRecord::getCreatedAt)
        );
    }

    @Override
    public Long createPricingRule(PricingRule rule) {
        pricingRuleMapper.insert(rule);
        return rule.getId();
    }

    @Override
    public boolean updatePricingRule(PricingRule rule) {
        return pricingRuleMapper.updateById(rule) > 0;
    }

    @Override
    public List<PricingRule> listPricingRules() {
        return pricingRuleMapper.selectList(
                new LambdaQueryWrapper<PricingRule>()
                        .eq(PricingRule::getEnabled, 1)
                        .orderByAsc(PricingRule::getResourceType)
        );
    }

    @Override
    public PricingRule getPricingRule(Long id) {
        return pricingRuleMapper.selectById(id);
    }

    @Override
    public boolean deletePricingRule(Long id) {
        return pricingRuleMapper.deleteById(id) > 0;
    }

    @Override
    @Transactional
    public Long generateBillingCycle(Long tenantId, String cycleType) {
        LocalDate now = LocalDate.now();
        LocalDate cycleStart;
        LocalDate cycleEnd;
        String cycleCode;

        if ("monthly".equals(cycleType)) {
            cycleStart = now.withDayOfMonth(1);
            cycleEnd = now.withDayOfMonth(now.lengthOfMonth());
            cycleCode = now.format(DateTimeFormatter.ofPattern("yyyyMM"));
        } else if ("weekly".equals(cycleType)) {
            cycleStart = now.minusDays(now.getDayOfWeek().getValue() - 1);
            cycleEnd = cycleStart.plusDays(6);
            cycleCode = now.format(DateTimeFormatter.ofPattern("yyyy'W'ww"));
        } else {
            cycleStart = now;
            cycleEnd = now;
            cycleCode = now.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        }

        BillingCycle existing = billingCycleMapper.selectOne(
                new LambdaQueryWrapper<BillingCycle>()
                        .eq(BillingCycle::getTenantId, tenantId)
                        .eq(BillingCycle::getCycleCode, cycleCode)
                        .eq(BillingCycle::getCycleType, cycleType)
        );
        if (existing != null) {
            return existing.getId();
        }

        BillingCycle cycle = new BillingCycle();
        cycle.setTenantId(tenantId);
        cycle.setCycleType(cycleType);
        cycle.setCycleCode(cycleCode);
        cycle.setCycleStart(cycleStart);
        cycle.setCycleEnd(cycleEnd);
        cycle.setStatus("unpaid");
        cycle.setTotalAmount(BigDecimal.ZERO);
        billingCycleMapper.insert(cycle);

        generateBillingItems(cycle);

        return cycle.getId();
    }

    private void generateBillingItems(BillingCycle cycle) {
        LocalDateTime start = cycle.getCycleStart().atStartOfDay();
        LocalDateTime end = cycle.getCycleEnd().atTime(LocalTime.MAX);

        List<UsageRecord> usageRecords = usageRecordMapper.selectList(
                new LambdaQueryWrapper<UsageRecord>()
                        .eq(UsageRecord::getTenantId, cycle.getTenantId())
                        .ge(UsageRecord::getStartTime, start)
                        .le(UsageRecord::getEndTime, end)
        );

        Map<String, Long> usageSummary = new HashMap<>();
        for (UsageRecord record : usageRecords) {
            usageSummary.merge(record.getResourceType(), record.getUsageAmount(), Long::sum);
        }

        BigDecimal totalAmount = BigDecimal.ZERO;
        for (Map.Entry<String, Long> entry : usageSummary.entrySet()) {
            String resourceType = entry.getKey();
            Long usageAmount = entry.getValue();

            PricingRule rule = findPricingRule(resourceType);
            if (rule == null) {
                continue;
            }

            BigDecimal unitPrice = rule.getUnitPrice();
            BigDecimal totalPrice = unitPrice.multiply(BigDecimal.valueOf(usageAmount))
                    .setScale(2, RoundingMode.HALF_UP);

            BillingItem item = new BillingItem();
            item.setTenantId(cycle.getTenantId());
            item.setCycleId(cycle.getId());
            item.setResourceType(resourceType);
            item.setUsageAmount(usageAmount);
            item.setUnitPrice(unitPrice);
            item.setTotalPrice(totalPrice);
            item.setUnit(rule.getUnit());
            billingItemMapper.insert(item);

            totalAmount = totalAmount.add(totalPrice);
        }

        cycle.setTotalAmount(totalAmount);
        billingCycleMapper.updateById(cycle);
    }

    private PricingRule findPricingRule(String resourceType) {
        return pricingRuleMapper.selectOne(
                new LambdaQueryWrapper<PricingRule>()
                        .eq(PricingRule::getResourceType, resourceType)
                        .eq(PricingRule::getEnabled, 1)
                        .le(PricingRule::getEffectiveDate, LocalDate.now())
                        .and(w -> w.isNull(PricingRule::getExpiryDate)
                                .or().ge(PricingRule::getExpiryDate, LocalDate.now()))
                        .orderByDesc(PricingRule::getEffectiveDate)
                        .last("LIMIT 1")
        );
    }

    @Override
    public BillingCycle getBillingCycle(Long id) {
        return billingCycleMapper.selectById(id);
    }

    @Override
    public List<BillingCycle> listBillingCycles(Long tenantId, String status) {
        return billingCycleMapper.selectList(
                new LambdaQueryWrapper<BillingCycle>()
                        .eq(BillingCycle::getTenantId, tenantId)
                        .eq(status != null, BillingCycle::getStatus, status)
                        .orderByDesc(BillingCycle::getCycleStart)
        );
    }

    @Override
    public List<BillingItem> listBillingItems(Long cycleId) {
        return billingItemMapper.selectList(
                new LambdaQueryWrapper<BillingItem>()
                        .eq(BillingItem::getCycleId, cycleId)
                        .orderByAsc(BillingItem::getResourceType)
        );
    }

    @Override
    public BigDecimal calculateUsageCost(Long tenantId, String resourceType, Long startTime, Long endTime) {
        LocalDateTime start = LocalDateTime.ofInstant(new Date(startTime).toInstant(), java.time.ZoneId.systemDefault());
        LocalDateTime end = LocalDateTime.ofInstant(new Date(endTime).toInstant(), java.time.ZoneId.systemDefault());

        List<UsageRecord> records = usageRecordMapper.selectList(
                new LambdaQueryWrapper<UsageRecord>()
                        .eq(UsageRecord::getTenantId, tenantId)
                        .eq(UsageRecord::getResourceType, resourceType)
                        .ge(UsageRecord::getStartTime, start)
                        .le(UsageRecord::getEndTime, end)
        );

        long totalUsage = records.stream().mapToLong(UsageRecord::getUsageAmount).sum();
        PricingRule rule = findPricingRule(resourceType);

        if (rule == null) {
            return BigDecimal.ZERO;
        }

        return rule.getUnitPrice().multiply(BigDecimal.valueOf(totalUsage))
                .setScale(2, RoundingMode.HALF_UP);
    }

    @Override
    public Map<String, Object> getTenantBillingSummary(Long tenantId, String cycleCode) {
        BillingCycle cycle = billingCycleMapper.selectOne(
                new LambdaQueryWrapper<BillingCycle>()
                        .eq(BillingCycle::getTenantId, tenantId)
                        .eq(BillingCycle::getCycleCode, cycleCode)
        );

        if (cycle == null) {
            throw new BusinessException("账单周期不存在");
        }

        List<BillingItem> items = listBillingItems(cycle.getId());

        Map<String, Object> summary = new HashMap<>();
        summary.put("cycle", cycle);
        summary.put("items", items);
        summary.put("itemCount", items.size());

        BigDecimal totalAmount = items.stream()
                .map(BillingItem::getTotalPrice)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        summary.put("totalAmount", totalAmount);

        return summary;
    }

    @Override
    public boolean processPayment(Long cycleId, BigDecimal amount) {
        BillingCycle cycle = billingCycleMapper.selectById(cycleId);
        if (cycle == null) {
            throw new BusinessException("账单周期不存在");
        }

        if (amount.compareTo(cycle.getTotalAmount()) < 0) {
            throw new BusinessException("支付金额不足");
        }

        cycle.setStatus("paid");
        cycle.setPaidAt(LocalDateTime.now());
        return billingCycleMapper.updateById(cycle) > 0;
    }

    @Override
    @Scheduled(cron = "0 0 1 1 * ?")
    public void generateMonthlyBills() {
        log.info("开始生成月度账单");
        Set<Long> tenantIds = usageRecordMapper.selectList(
                new LambdaQueryWrapper<UsageRecord>()
                        .select(UsageRecord::getTenantId)
                        .groupBy(UsageRecord::getTenantId)
        ).stream().map(UsageRecord::getTenantId).collect(java.util.stream.Collectors.toSet());

        for (Long tenantId : tenantIds) {
            try {
                generateBillingCycle(tenantId, "monthly");
            } catch (Exception e) {
                log.error("生成月度账单失败, tenantId: {}", tenantId, e);
            }
        }
        log.info("月度账单生成完成, 处理租户数量: {}", tenantIds.size());
    }

    @Override
    public Map<String, Object> getPricingEstimate(Long tenantId, Map<String, Long> estimatedUsage) {
        Map<String, Object> estimate = new HashMap<>();
        List<Map<String, Object>> itemEstimates = new ArrayList<>();
        BigDecimal totalEstimate = BigDecimal.ZERO;

        for (Map.Entry<String, Long> entry : estimatedUsage.entrySet()) {
            String resourceType = entry.getKey();
            Long usageAmount = entry.getValue();

            PricingRule rule = findPricingRule(resourceType);
            if (rule == null) {
                continue;
            }

            BigDecimal unitPrice = rule.getUnitPrice();
            BigDecimal totalPrice = unitPrice.multiply(BigDecimal.valueOf(usageAmount))
                    .setScale(2, RoundingMode.HALF_UP);

            Map<String, Object> item = new HashMap<>();
            item.put("resourceType", resourceType);
            item.put("estimatedUsage", usageAmount);
            item.put("unitPrice", unitPrice);
            item.put("unit", rule.getUnit());
            item.put("estimatedCost", totalPrice);
            itemEstimates.add(item);

            totalEstimate = totalEstimate.add(totalPrice);
        }

        estimate.put("tenantId", tenantId);
        estimate.put("items", itemEstimates);
        estimate.put("totalEstimate", totalEstimate);
        estimate.put("currency", "CNY");

        return estimate;
    }
}
