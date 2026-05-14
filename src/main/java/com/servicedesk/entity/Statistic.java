package com.servicedesk.entity;

import lombok.Data;

import javax.persistence.*;
import java.time.LocalDate;

@Data
@Entity
@Table(name = "statistics")
public class Statistic {
    @Id
    @Column(name = "stat_id", length = 50)
    private String statId;

    @Column(name = "stat_date", nullable = false, unique = true)
    private LocalDate statDate;

    @Column(name = "total_tickets", nullable = false)
    private Integer totalTickets = 0;

    @Column(name = "resolved_tickets", nullable = false)
    private Integer resolvedTickets = 0;

    @Column(name = "avg_response_time")
    private Long avgResponseTime;

    @Column(name = "avg_resolution_time")
    private Long avgResolutionTime;

    @Column(name = "satisfaction_rate")
    private Double satisfactionRate;
}
