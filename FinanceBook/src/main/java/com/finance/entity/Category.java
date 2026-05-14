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
@Table(name = "categories")
public class Category {

    @Id
    @Column(name = "category_id", nullable = false, length = 50)
    private String categoryId;

    @Column(name = "category_name", nullable = false, length = 100)
    private String categoryName;

    @Column(name = "category_type", nullable = false, length = 20)
    private String categoryType;

    @Column(name = "category_parent", length = 50)
    private String categoryParent;

    @Column(name = "category_status", nullable = false, length = 20)
    private String categoryStatus;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
