package com.invoice.mgmt.common.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InvoiceType {
    private String typeId;
    private String typeCode;
    private String typeName;
    private BigDecimal taxRate;
    private Boolean enabled;
    private String description;
    private Instant createdAt;
    private Instant updatedAt;
}
