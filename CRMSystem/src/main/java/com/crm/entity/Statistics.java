package com.crm.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Entity
@Table(name = "statistics")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Statistics {
    @Id
    @Column(name = "stat_id", nullable = false, unique = true)
    private String statId;

    @Column(name = "stat_month", nullable = false, unique = true)
    private String statMonth;

    @Column(name = "customer_count")
    private Integer customerCount = 0;

    @Column(name = "follow_count")
    private Integer followCount = 0;

    @Column(name = "opportunity_count")
    private Integer opportunityCount = 0;

    @Column(name = "deal_amount")
    private Double dealAmount = 0.0;

    @Column(name = "success_count")
    private Integer successCount = 0;

    @Column(name = "fail_count")
    private Integer failCount = 0;
}
