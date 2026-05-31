package com.contractai.metering.dto;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
public class UsageRecordCreateDTO {
    private String resourceType;
    private Long usageAmount;
    private String unit;
    private LocalDateTime usageTime;
    private String source;
    private String sourceId;
    private Map<String, Object> attributes;
}

@Data
class UsageQueryDTO {
    private String resourceType;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private String source;
}

@Data
class BillingPlanCreateDTO {
    private String planCode;
    private String planName;
    private String planType;
    private BigDecimal price;
    private String billingCycle;
    private LocalDate startDate;
    private LocalDate endDate;
    private Map<String, Object> includedResources;
    private Map<String, Object> overageRates;
}

@Data
class PriceRuleCreateDTO {
    private String resourceType;
    private String billingMode;
    private BigDecimal pricePerUnit;
    private List<Map<String, Object>> tierConfig;
    private String currency;
    private LocalDateTime effectiveFrom;
    private LocalDateTime effectiveTo;
}

@Data
class BillingQueryDTO {
    private String billingPeriod;
    private String status;
    private LocalDate startDate;
    private LocalDate endDate;
}

@Data
class BillPaymentDTO {
    private Long billId;
    private BigDecimal paidAmount;
    private String paymentMethod;
    private String transactionId;
}

@Data
class UsageStatsDTO {
    private String resourceType;
    private Long totalUsage;
    private String unit;
    private BigDecimal estimatedCost;
    private Map<String, Long> dailyUsage;
}
