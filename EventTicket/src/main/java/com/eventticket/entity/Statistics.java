package com.eventticket.entity;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(name = "statistics")
public class Statistics {
    @Id
    @Column(name = "stat_id", length = 50)
    private String statId;

    @Column(name = "stat_month", length = 7, nullable = false)
    private String statMonth;

    @Column(name = "event_count", nullable = false)
    private Integer eventCount;

    @Column(name = "ticket_count", nullable = false)
    private Integer ticketCount;

    @Column(name = "total_amount", nullable = false)
    private Long totalAmount;

    @Column(name = "admission_count", nullable = false)
    private Integer admissionCount;
}
