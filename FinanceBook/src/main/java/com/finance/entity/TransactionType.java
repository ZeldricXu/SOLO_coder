package com.finance.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "transaction_types")
public class TransactionType {

    @Id
    @Column(name = "type_id", nullable = false, length = 50)
    private String typeId;

    @Column(name = "type_code", nullable = false, length = 50, unique = true)
    private String typeCode;

    @Column(name = "type_name", nullable = false, length = 100)
    private String typeName;

    @Column(name = "type_direction", nullable = false, length = 20)
    private String typeDirection;

    @Column(name = "affects_balance", nullable = false)
    private Boolean affectsBalance;

    @Column(name = "requires_category", nullable = false)
    private Boolean requiresCategory;

    @Column(name = "type_description", length = 500)
    private String typeDescription;

    @Column(name = "type_status", nullable = false, length = 20)
    private String typeStatus;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
