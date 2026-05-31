package com.smartflow.meteringbilling.service;

import com.smartflow.common.exception.BusinessException;
import com.smartflow.common.utils.IdGenerator;
import com.smartflow.common.utils.JsonUtils;
import com.smartflow.persistence.entity.BillingInvoice;
import com.smartflow.persistence.entity.Tenant;
import com.smartflow.persistence.entity.TenantQuota;
import com.smartflow.persistence.entity.TenantUsage;
import com.smartflow.persistence.mapper.BillingInvoiceMapper;
import com.smartflow.persistence.mapper.TenantMapper;
import com.smartflow.persistence.mapper.TenantQuotaMapper;
import com.smartflow.persistence.mapper.TenantUsageMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
public class MeteringBillingService {

    private final TenantUsageMapper usageMapper;
    private final BillingInvoiceMapper invoiceMapper;
    private final TenantMapper tenantMapper;
    private final TenantQuotaMapper quotaMapper;

    @Transactional
    public TenantUsage recordUsage(Long tenantId, String resourceType, Long amount, String dimension) {
        Tenant tenant = tenantMapper.selectById(tenantId);
        if (tenant == null) {
            throw new BusinessException("租户不存在");
        }

        TenantQuota quota = quotaMapper.selectOne(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<TenantQuota>()
                .eq(TenantQuota::getTenantId, tenantId)
                .eq(TenantQuota::getResourceType, resourceType)
        );

        if (quota != null) {
            long newUsage = quota.getUsedAmount() + amount;
            if (newUsage > quota.getQuotaLimit()) {
                throw new BusinessException("资源使用超出配额限制");
            }
            quota.setUsedAmount(newUsage);
            quotaMapper.updateById(quota);
        }

        BigDecimal unitPrice = getUnitPrice(resourceType);
        BigDecimal totalCost = unitPrice.multiply(BigDecimal.valueOf(amount))
                .setScale(2, RoundingMode.HALF_UP);

        TenantUsage usage = new TenantUsage();
        usage.setId(IdGenerator.generateId());
        usage.setTenantId(tenantId);
        usage.setResourceType(resourceType);
        usage.setUsageAmount(amount);
        usage.setQuotaLimit(quota != null ? quota.getQuotaLimit() : null);
        usage.setUnitPrice(unitPrice);
        usage.setTotalCost(totalCost);
        usage.setDimension(dimension);
        usage.setPeriodStart(LocalDateTime.now().withDayOfMonth(1).withHour(0).withMinute(0).withSecond(0));
        usage.setPeriodEnd(LocalDateTime.now());
        usageMapper.insert(usage);

        return usage;
    }

    private BigDecimal getUnitPrice(String resourceType) {
        Map<String, BigDecimal> priceMap = new HashMap<>();
        priceMap.put("TICKET_COUNT", new BigDecimal("0.10"));
        priceMap.put("STORAGE", new BigDecimal("0.05"));
        priceMap.put("API_CALL", new BigDecimal("0.001"));
        priceMap.put("APPROVAL_FLOW", new BigDecimal("0.50"));
        priceMap.put("DOCUMENT_COMPARE", new BigDecimal("1.00"));
        return priceMap.getOrDefault(resourceType, new BigDecimal("0.01"));
    }

    @Transactional
    public BillingInvoice generateMonthlyInvoice(Long tenantId, Integer year, Integer month) {
        Tenant tenant = tenantMapper.selectById(tenantId);
        if (tenant == null) {
            throw new BusinessException("租户不存在");
        }

        LocalDateTime periodStart = LocalDateTime.of(year, month, 1, 0, 0, 0);
        LocalDateTime periodEnd = periodStart.plusMonths(1).minusSeconds(1);

        List<TenantUsage> usages = usageMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<TenantUsage>()
                .eq(TenantUsage::getTenantId, tenantId)
                .between(TenantUsage::getCreatedAt, periodStart, periodEnd)
        );

        Map<String, BigDecimal> costByType = new HashMap<>();
        BigDecimal totalAmount = BigDecimal.ZERO;

        for (TenantUsage usage : usages) {
            BigDecimal cost = usage.getTotalCost() != null ? usage.getTotalCost() : BigDecimal.ZERO;
            costByType.merge(usage.getResourceType(), cost, BigDecimal::add);
            totalAmount = totalAmount.add(cost);
        }

        BillingInvoice invoice = new BillingInvoice();
        invoice.setId(IdGenerator.generateId());
        invoice.setInvoiceNo("INV-" + tenantId + "-" + year + String.format("%02d", month) + "-" + IdGenerator.generateStrId().substring(0, 8));
        invoice.setTenantId(tenantId);
        invoice.setTenantName(tenant.getTenantName());
        invoice.setTotalAmount(totalAmount);
        invoice.setPaidAmount(BigDecimal.ZERO);
        invoice.setStatus(0);
        invoice.setBillingPeriodStart(periodStart);
        invoice.setBillingPeriodEnd(periodEnd);
        invoice.setDueDate(periodEnd.plusDays(15));
        invoice.setItems(JsonUtils.toJson(costByType));
        invoiceMapper.insert(invoice);

        return invoice;
    }

    public Map<String, Object> getTenantUsageSummary(Long tenantId, Integer year, Integer month) {
        LocalDateTime periodStart = LocalDateTime.of(year, month, 1, 0, 0, 0);
        LocalDateTime periodEnd = periodStart.plusMonths(1).minusSeconds(1);

        List<TenantUsage> usages = usageMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<TenantUsage>()
                .eq(TenantUsage::getTenantId, tenantId)
                .between(TenantUsage::getCreatedAt, periodStart, periodEnd)
        );

        Map<String, Long> usageByType = new HashMap<>();
        Map<String, BigDecimal> costByType = new HashMap<>();
        BigDecimal totalCost = BigDecimal.ZERO;
        long totalUsage = 0;

        for (TenantUsage usage : usages) {
            usageByType.merge(usage.getResourceType(), usage.getUsageAmount(), Long::sum);
            BigDecimal cost = usage.getTotalCost() != null ? usage.getTotalCost() : BigDecimal.ZERO;
            costByType.merge(usage.getResourceType(), cost, BigDecimal::add);
            totalCost = totalCost.add(cost);
            totalUsage += usage.getUsageAmount();
        }

        List<TenantQuota> quotas = quotaMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<TenantQuota>()
                .eq(TenantQuota::getTenantId, tenantId)
        );

        Map<String, Object> result = new HashMap<>();
        result.put("tenantId", tenantId);
        result.put("period", year + "-" + String.format("%02d", month));
        result.put("totalUsage", totalUsage);
        result.put("totalCost", totalCost);
        result.put("usageByType", usageByType);
        result.put("costByType", costByType);
        result.put("quotas", quotas);
        return result;
    }

    public Map<String, Object> getInvoiceList(Long tenantId, Integer status) {
        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<BillingInvoice> query = 
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<BillingInvoice>()
                .eq(BillingInvoice::getTenantId, tenantId)
                .orderByDesc(BillingInvoice::getBillingPeriodStart);

        if (status != null) {
            query.eq(BillingInvoice::getStatus, status);
        }

        List<BillingInvoice> invoices = invoiceMapper.selectList(query);

        Map<String, Object> result = new HashMap<>();
        result.put("total", invoices.size());
        result.put("invoices", invoices);
        return result;
    }

    @Transactional
    public boolean payInvoice(Long invoiceId) {
        BillingInvoice invoice = invoiceMapper.selectById(invoiceId);
        if (invoice == null) {
            throw new BusinessException("账单不存在");
        }
        if (invoice.getStatus() == 1) {
            throw new BusinessException("账单已支付");
        }

        Tenant tenant = tenantMapper.selectById(invoice.getTenantId());
        if (tenant == null) {
            throw new BusinessException("租户不存在");
        }

        if (tenant.getAccountBalance().compareTo(invoice.getTotalAmount()) < 0) {
            throw new BusinessException("账户余额不足");
        }

        tenant.setAccountBalance(tenant.getAccountBalance().subtract(invoice.getTotalAmount()));
        tenantMapper.updateById(tenant);

        invoice.setPaidAmount(invoice.getTotalAmount());
        invoice.setStatus(1);
        invoice.setPaidAt(LocalDateTime.now());
        invoiceMapper.updateById(invoice);

        return true;
    }

    public List<TenantUsage> getUsageRecords(Long tenantId, String resourceType, LocalDateTime startTime, LocalDateTime endTime) {
        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<TenantUsage> query = 
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<TenantUsage>()
                .eq(TenantUsage::getTenantId, tenantId)
                .orderByDesc(TenantUsage::getCreatedAt);

        if (resourceType != null && !resourceType.isEmpty()) {
            query.eq(TenantUsage::getResourceType, resourceType);
        }
        if (startTime != null) {
            query.ge(TenantUsage::getCreatedAt, startTime);
        }
        if (endTime != null) {
            query.le(TenantUsage::getCreatedAt, endTime);
        }

        return usageMapper.selectList(query);
    }
}
