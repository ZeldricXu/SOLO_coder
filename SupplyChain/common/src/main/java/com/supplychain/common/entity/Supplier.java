package com.supplychain.common.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Supplier implements Serializable {
    private String supplierId;
    private String supplierName;
    private String supplierType;
    private String supplierContact;
    private String supplierAddress;
    private String supplierStatus;
    private Double supplierRating;
    private LocalDateTime registeredAt;
    private LocalDateTime updatedAt;
}
