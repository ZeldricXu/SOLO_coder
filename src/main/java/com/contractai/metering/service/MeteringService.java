package com.contractai.metering.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.contractai.common.context.TenantContext;
import com.contractai.common.dto.PageQuery;
import com.contractai.common.dto.PageResult;
import com.contractai.common.exception.BusinessException;
import com.contractai.common.exception.ValidationException;
import com.contractai.metering.dto.*;
import com.contractai.metering.entity.*;
import com.contractai.metering.mapper.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class MeteringService {

    private final UsageRecordMapper usageRecordMapper;
    private final BillingPlanMapper billingPlanMapper;
    private final PriceRuleMapper priceRuleMapper;
    private final BillMapper billMapper;
    private final BillItemMapper billItemMapper;

    @Transactional(rollbackFor = Exception.class)
    public UsageRecord recordUsage(UsageRecordCreateDTO dto) {
        Long tenantId = TenantContext.getTenantId();
        if (!StringUtils.hasText(dto.getResourceType())) {
            throw new ValidationException("资源类型不能为空");
        }
        if (dto.getUsageAmount() == null || dto.getUsageAmount() < 0) {
            throw new ValidationException("使用量不能为负数");
        }

        UsageRecord record = new UsageRecord();
        record.setTenantId(tenantId);
        record.setResourceType(dto.getResourceType());
        record.setUsageAmount(dto.getUsageAmount());
        record.setUnit(dto.getUnit() != null ? dto.getUnit() : "count");
        record.setUsageTime(dto.getUsageTime() != null ? dto.getUsageTime() : LocalDateTime.now());
        record.setSource(dto.getSource());
        record.setSourceId(dto.getSourceId());
        record.setAttributes(dto.getAttributes());

        usageRecordMapper.insert(record);
        log.debug("记录用量: tenantId={}, resourceType={}, amount={}", 
                tenantId, dto.getResourceType(), dto.getUsageAmount());
        return record;
    }

    @Async
    @Transactional(rollbackFor = Exception.class)
    public void asyncRecordUsage(Long tenantId, String resourceType, Long amount, String source) {
        UsageRecord record = new UsageRecord();
        record.setTenantId(tenantId);
        record.setResourceType(resourceType);
        record.setUsageAmount(amount);
        record.setUnit("count");
        record.setUsageTime(LocalDateTime.now());
        record.setSource(source);
        usageRecordMapper.insert(record);
    }

    public PageResult<UsageRecord> listUsageRecords(PageQuery pageQuery, UsageQueryDTO query) {
        Long tenantId = TenantContext.getTenantId();
        Page<UsageRecord> page = new Page<>(pageQuery.getPageNum(), pageQuery.getPageSize());
        
        LambdaQueryWrapper<UsageRecord> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(UsageRecord::getTenantId, tenantId)
               .eq(StringUtils.hasText(query.getResourceType()), UsageRecord::getResourceType, query.getResourceType())
               .eq(StringUtils.hasText(query.getSource()), UsageRecord::getSource, query.getSource())
               .ge(query.getStartTime() != null, UsageRecord::getUsageTime, query.getStartTime())
               .le(query.getEndTime() != null, UsageRecord::getUsageTime, query.getEndTime())
               .orderByDesc(UsageRecord::getUsageTime);
        
        usageRecordMapper.selectPage(page, wrapper);
        return new PageResult<>(page.getTotal(), page.getRecords(), pageQuery.getPageNum(), pageQuery.getPageSize());
    }

    public List<UsageStatsDTO> getUsageStats(UsageQueryDTO query) {
        Long tenantId = TenantContext.getTenantId();
        
        LambdaQueryWrapper<UsageRecord> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(UsageRecord::getTenantId, tenantId)
               .ge(query.getStartTime() != null, UsageRecord::getUsageTime, query.getStartTime())
               .le(query.getEndTime() != null, UsageRecord::getUsageTime, query.getEndTime());
        
        List<UsageRecord> records = usageRecordMapper.selectList(wrapper);
        
        Map<String, List<UsageRecord>> groupedByResource = records.stream()
                .collect(Collectors.groupingBy(UsageRecord::getResourceType));
        
        List<UsageStatsDTO> stats = new ArrayList<>();
        for (Map.Entry<String, List<UsageRecord>> entry : groupedByResource.entrySet()) {
            UsageStatsDTO stat = new UsageStatsDTO();
            stat.setResourceType(entry.getKey());
            
            long total = entry.getValue().stream()
                    .mapToLong(UsageRecord::getUsageAmount)
                    .sum();
            stat.setTotalUsage(total);
            stat.setUnit(entry.getValue().get(0).getUnit());
            
            BigDecimal unitPrice = getUnitPrice(entry.getKey());
            stat.setEstimatedCost(unitPrice.multiply(BigDecimal.valueOf(total))
                    .setScale(2, RoundingMode.HALF_UP));
            
            Map<String, Long> dailyUsage = entry.getValue().stream()
                    .collect(Collectors.groupingBy(
                            r -> r.getUsageTime().toLocalDate().toString(),
                            Collectors.summingLong(UsageRecord::getUsageAmount)
                    ));
            stat.setDailyUsage(new TreeMap<>(dailyUsage));
            
            stats.add(stat);
        }
        
        return stats;
    }

    private BigDecimal getUnitPrice(String resourceType) {
        Long tenantId = TenantContext.getTenantIdSafe();
        if (tenantId == null) return BigDecimal.ZERO;
        
        PriceRule rule = priceRuleMapper.selectOne(
                new LambdaQueryWrapper<PriceRule>()
                        .eq(PriceRule::getTenantId, tenantId)
                        .eq(PriceRule::getResourceType, resourceType)
                        .le(PriceRule::getEffectiveFrom, LocalDateTime.now())
                        .and(w -> w.isNull(PriceRule::getEffectiveTo)
                                .or().gt(PriceRule::getEffectiveTo, LocalDateTime.now()))
                        .orderByDesc(PriceRule::getEffectiveFrom)
                        .last("limit 1"));
        
        return rule != null ? rule.getPricePerUnit() : BigDecimal.ZERO;
    }

    @Transactional(rollbackFor = Exception.class)
    public PriceRule createPriceRule(PriceRuleCreateDTO dto) {
        Long tenantId = TenantContext.getTenantId();
        if (!StringUtils.hasText(dto.getResourceType())) {
            throw new ValidationException("资源类型不能为空");
        }
        if (dto.getPricePerUnit() == null) {
            throw new ValidationException("单价不能为空");
        }

        PriceRule rule = new PriceRule();
        rule.setTenantId(tenantId);
        rule.setResourceType(dto.getResourceType());
        rule.setBillingMode(dto.getBillingMode() != null ? dto.getBillingMode() : "fixed");
        rule.setPricePerUnit(dto.getPricePerUnit());
        rule.setTierConfig(dto.getTierConfig());
        rule.setCurrency(dto.getCurrency() != null ? dto.getCurrency() : "CNY");
        rule.setEffectiveFrom(dto.getEffectiveFrom() != null ? dto.getEffectiveFrom() : LocalDateTime.now());
        rule.setEffectiveTo(dto.getEffectiveTo());

        priceRuleMapper.insert(rule);
        log.info("创建价格规则成功: tenantId={}, resourceType={}, price={}", 
                tenantId, dto.getResourceType(), dto.getPricePerUnit());
        return rule;
    }

    @Transactional(rollbackFor = Exception.class)
    public BillingPlan createBillingPlan(BillingPlanCreateDTO dto) {
        Long tenantId = TenantContext.getTenantId();
        if (!StringUtils.hasText(dto.getPlanCode())) {
            throw new ValidationException("套餐编码不能为空");
        }
        if (!StringUtils.hasText(dto.getPlanName())) {
            throw new ValidationException("套餐名称不能为空");
        }

        BillingPlan plan = new BillingPlan();
        plan.setTenantId(tenantId);
        plan.setPlanCode(dto.getPlanCode());
        plan.setPlanName(dto.getPlanName());
        plan.setPlanType(dto.getPlanType() != null ? dto.getPlanType() : "standard");
        plan.setPrice(dto.getPrice() != null ? dto.getPrice() : BigDecimal.ZERO);
        plan.setBillingCycle(dto.getBillingCycle() != null ? dto.getBillingCycle() : "monthly");
        plan.setStartDate(dto.getStartDate() != null ? dto.getStartDate() : LocalDate.now());
        plan.setEndDate(dto.getEndDate());
        plan.setStatus(1);
        plan.setIncludedResources(dto.getIncludedResources());
        plan.setOverageRates(dto.getOverageRates());

        billingPlanMapper.insert(plan);
        log.info("创建计费套餐成功: tenantId={}, planCode={}", tenantId, dto.getPlanCode());
        return plan;
    }

    public List<PriceRule> listPriceRules() {
        Long tenantId = TenantContext.getTenantId();
        return priceRuleMapper.selectList(
                new LambdaQueryWrapper<PriceRule>()
                        .eq(PriceRule::getTenantId, tenantId)
                        .orderByDesc(PriceRule::getCreatedAt));
    }

    public List<BillingPlan> listBillingPlans() {
        Long tenantId = TenantContext.getTenantId();
        return billingPlanMapper.selectList(
                new LambdaQueryWrapper<BillingPlan>()
                        .eq(BillingPlan::getTenantId, tenantId)
                        .orderByDesc(BillingPlan::getCreatedAt));
    }

    @Scheduled(cron = "0 0 2 1 * ?")
    @Transactional(rollbackFor = Exception.class)
    public void generateMonthlyBills() {
        log.info("开始生成月度账单...");
        
        List<Long> tenantIds = usageRecordMapper.selectObjs(
                new LambdaQueryWrapper<UsageRecord>()
                        .select(UsageRecord::getTenantId)
                        .groupBy(UsageRecord::getTenantId))
                .stream()
                .map(o -> (Long) o)
                .collect(Collectors.toList());
        
        YearMonth lastMonth = YearMonth.now().minusMonths(1);
        for (Long tenantId : tenantIds) {
            try {
                TenantContext.setTenantId(tenantId);
                generateBill(tenantId, lastMonth);
            } catch (Exception e) {
                log.error("生成账单失败: tenantId={}, period={}", tenantId, lastMonth, e);
            } finally {
                TenantContext.clearTenantId();
            }
        }
        
        log.info("月度账单生成完成");
    }

    @Transactional(rollbackFor = Exception.class)
    public Bill generateBill(Long tenantId, YearMonth billingPeriod) {
        String periodStr = billingPeriod.format(DateTimeFormatter.ofPattern("yyyy-MM"));
        
        Bill existing = billMapper.selectOne(
                new LambdaQueryWrapper<Bill>()
                        .eq(Bill::getTenantId, tenantId)
                        .eq(Bill::getBillingPeriod, periodStr));
        if (existing != null) {
            log.info("账单已存在: tenantId={}, period={}", tenantId, periodStr);
            return existing;
        }

        LocalDateTime startOfMonth = billingPeriod.atDay(1).atStartOfDay();
        LocalDateTime endOfMonth = billingPeriod.atEndOfMonth().atTime(23, 59, 59);

        LambdaQueryWrapper<UsageRecord> usageWrapper = new LambdaQueryWrapper<>();
        usageWrapper.eq(UsageRecord::getTenantId, tenantId)
                    .ge(UsageRecord::getUsageTime, startOfMonth)
                    .le(UsageRecord::getUsageTime, endOfMonth);
        
        List<UsageRecord> usageRecords = usageRecordMapper.selectList(usageWrapper);
        
        Map<String, Long> usageByResource = usageRecords.stream()
                .collect(Collectors.groupingBy(
                        UsageRecord::getResourceType,
                        Collectors.summingLong(UsageRecord::getUsageAmount)
                ));

        List<BillItem> billItems = new ArrayList<>();
        BigDecimal totalAmount = BigDecimal.ZERO;
        List<Map<String, Object>> billItemList = new ArrayList<>();

        for (Map.Entry<String, Long> entry : usageByResource.entrySet()) {
            String resourceType = entry.getKey();
            Long amount = entry.getValue();
            
            BigDecimal unitPrice = getUnitPrice(resourceType);
            BigDecimal itemAmount = unitPrice.multiply(BigDecimal.valueOf(amount))
                    .setScale(2, RoundingMode.HALF_UP);
            
            BillItem billItem = new BillItem();
            billItem.setTenantId(tenantId);
            billItem.setResourceType(resourceType);
            billItem.setUsageAmount(amount);
            billItem.setUnit("count");
            billItem.setUnitPrice(unitPrice);
            billItem.setAmount(itemAmount);
            billItem.setDescription(resourceType + "使用费用");
            billItems.add(billItem);
            
            Map<String, Object> itemMap = new HashMap<>();
            itemMap.put("resourceType", resourceType);
            itemMap.put("usageAmount", amount);
            itemMap.put("unitPrice", unitPrice);
            itemMap.put("amount", itemAmount);
            billItemList.add(itemMap);
            
            totalAmount = totalAmount.add(itemAmount);
        }

        String billNo = "BILL-" + tenantId + "-" + periodStr + "-" + 
                System.currentTimeMillis() % 10000;
        
        Bill bill = new Bill();
        bill.setTenantId(tenantId);
        bill.setBillNo(billNo);
        bill.setBillingPeriod(periodStr);
        bill.setTotalAmount(totalAmount);
        bill.setPaidAmount(BigDecimal.ZERO);
        bill.setStatus("unpaid");
        bill.setIssueDate(LocalDate.now());
        bill.setDueDate(LocalDate.now().plusDays(15));
        bill.setCurrency("CNY");
        bill.setBillItems(billItemList);
        
        Map<String, Object> summary = new HashMap<>();
        summary.put("totalRecords", usageRecords.size());
        summary.put("resourceTypes", usageByResource.size());
        summary.put("billingPeriod", periodStr);
        bill.setSummary(summary);

        billMapper.insert(bill);
        
        for (BillItem item : billItems) {
            item.setBillId(bill.getId());
            billItemMapper.insert(item);
        }

        log.info("生成账单成功: tenantId={}, billNo={}, totalAmount={}", 
                tenantId, billNo, totalAmount);
        return bill;
    }

    public PageResult<Bill> listBills(PageQuery pageQuery, BillingQueryDTO query) {
        Long tenantId = TenantContext.getTenantId();
        Page<Bill> page = new Page<>(pageQuery.getPageNum(), pageQuery.getPageSize());
        
        LambdaQueryWrapper<Bill> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Bill::getTenantId, tenantId)
               .eq(StringUtils.hasText(query.getBillingPeriod()), Bill::getBillingPeriod, query.getBillingPeriod())
               .eq(StringUtils.hasText(query.getStatus()), Bill::getStatus, query.getStatus())
               .ge(query.getStartDate() != null, Bill::getIssueDate, query.getStartDate())
               .le(query.getEndDate() != null, Bill::getIssueDate, query.getEndDate())
               .orderByDesc(Bill::getIssueDate);
        
        billMapper.selectPage(page, wrapper);
        return new PageResult<>(page.getTotal(), page.getRecords(), pageQuery.getPageNum(), pageQuery.getPageSize());
    }

    public Bill getBill(Long id) {
        Long tenantId = TenantContext.getTenantId();
        Bill bill = billMapper.selectOne(
                new LambdaQueryWrapper<Bill>()
                        .eq(Bill::getTenantId, tenantId)
                        .eq(Bill::getId, id));
        if (bill == null) {
            throw new BusinessException(404, "账单不存在");
        }
        return bill;
    }

    public List<BillItem> getBillItems(Long billId) {
        Long tenantId = TenantContext.getTenantId();
        return billItemMapper.selectList(
                new LambdaQueryWrapper<BillItem>()
                        .eq(BillItem::getTenantId, tenantId)
                        .eq(BillItem::getBillId, billId));
    }

    @Transactional(rollbackFor = Exception.class)
    public Bill processPayment(BillPaymentDTO dto) {
        Long tenantId = TenantContext.getTenantId();
        Bill bill = getBill(dto.getBillId());
        
        if ("paid".equals(bill.getStatus())) {
            throw new BusinessException(400, "账单已支付");
        }
        
        BigDecimal newPaidAmount = bill.getPaidAmount().add(dto.getPaidAmount());
        bill.setPaidAmount(newPaidAmount);
        bill.setPaidDate(LocalDate.now());
        
        if (newPaidAmount.compareTo(bill.getTotalAmount()) >= 0) {
            bill.setStatus("paid");
            log.info("账单已付清: billId={}, totalAmount={}", dto.getBillId(), bill.getTotalAmount());
        } else {
            bill.setStatus("partial");
            log.info("账单部分支付: billId={}, paidAmount={}", dto.getBillId(), newPaidAmount);
        }
        
        billMapper.updateById(bill);
        return bill;
    }

    public BigDecimal calculateCost(String resourceType, Long usageAmount) {
        BigDecimal unitPrice = getUnitPrice(resourceType);
        return unitPrice.multiply(BigDecimal.valueOf(usageAmount))
                .setScale(2, RoundingMode.HALF_UP);
    }

    public Map<String, Object> getBillingDashboard() {
        Long tenantId = TenantContext.getTenantId();
        
        UsageQueryDTO query = new UsageQueryDTO();
        query.setStartTime(YearMonth.now().atDay(1).atStartOfDay());
        query.setEndTime(LocalDateTime.now());
        List<UsageStatsDTO> currentMonthStats = getUsageStats(query);
        
        YearMonth lastMonth = YearMonth.now().minusMonths(1);
        Bill lastMonthBill = billMapper.selectOne(
                new LambdaQueryWrapper<Bill>()
                        .eq(Bill::getTenantId, tenantId)
                        .eq(Bill::getBillingPeriod, lastMonth.format(DateTimeFormatter.ofPattern("yyyy-MM"))));
        
        long unpaidCount = billMapper.selectCount(
                new LambdaQueryWrapper<Bill>()
                        .eq(Bill::getTenantId, tenantId)
                        .in(Bill::getStatus, "unpaid", "overdue", "partial"));
        
        Map<String, Object> dashboard = new HashMap<>();
        dashboard.put("currentMonthUsage", currentMonthStats);
        dashboard.put("currentMonthEstimatedCost", currentMonthStats.stream()
                .map(UsageStatsDTO::getEstimatedCost)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        dashboard.put("lastMonthBill", lastMonthBill);
        dashboard.put("unpaidBillsCount", unpaidCount);
        
        return dashboard;
    }
}
