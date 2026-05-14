package com.crm.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "categories")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Category {
    @Id
    @Column(name = "category_id", nullable = false, unique = true)
    private String categoryId;

    @Column(name = "category_name", nullable = false)
    private String categoryName;

    @Column(name = "category_type")
    private String categoryType;

    @Column(name = "category_code")
    private String categoryCode;

    @Column(name = "category_level")
    private Integer categoryLevel;

    @Column(name = "category_status")
    private String categoryStatus;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        if (categoryStatus == null) {
            categoryStatus = "active";
        }
    }
}
