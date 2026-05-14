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
@Table(name = "budget_types")
public class BudgetType {

    @Id
    @Column(name = "budget_type_id", nullable = false, length = 50)
    private String budgetTypeId;

    @Column(name = "budget_type_code", nullable = false, length = 50, unique = true)
    private String budgetTypeCode;

    @Column(name = "budget_type_name", nullable = false, length = 100)
    private String budgetTypeName;

    @Column(name = "category_pattern", nullable = false, length = 200)
    private String categoryPattern;

    @Column(name = "priority_level", nullable = false, length = 20)
    private String priorityLevel;

    @Column(name = "reminder_frequency_minutes", nullable = false)
    private Integer reminderFrequencyMinutes;

    @Column(name = "max_reminders_per_day", nullable = false)
    private Integer maxRemindersPerDay;

    @Column(name = "type_description", length = 500)
    private String typeDescription;

    @Column(name = "type_status", nullable = false, length = 20)
    private String typeStatus;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
