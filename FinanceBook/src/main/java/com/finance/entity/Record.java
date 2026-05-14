package com.finance.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "records")
public class Record {

    @Id
    @Column(name = "record_id", nullable = false, length = 50)
    private String recordId;

    @Column(name = "account_id", nullable = false, length = 50)
    private String accountId;

    @Column(name = "record_type", nullable = false, length = 20)
    private String recordType;

    @Column(name = "record_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal recordAmount;

    @Column(name = "record_category", nullable = false, length = 50)
    private String recordCategory;

    @Column(name = "record_time", nullable = false)
    private LocalDateTime recordTime;

    @Column(name = "record_desc", length = 500)
    private String recordDesc;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
