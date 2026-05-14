package com.survey.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "history_records")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class HistoryRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "business_type", nullable = false, length = 30)
    private String businessType;

    @Column(name = "business_id", nullable = false, length = 50)
    private String businessId;

    @Column(name = "action", nullable = false, length = 50)
    private String action;

    @Column(name = "detail", length = 1000)
    private String detail;

    @Column(name = "operator_id", length = 50)
    private String operatorId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
