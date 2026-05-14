package com.assetmanage.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "inventory_checks")
public class InventoryCheck {

    @Id
    @Column(name = "check_id", length = 64)
    private String checkId;

    @Column(name = "check_type")
    private String checkType;

    @Column(name = "check_department")
    private String checkDepartment;

    @Column(name = "check_status")
    private String checkStatus;

    @Column(name = "total_assets")
    private Integer totalAssets;

    @Column(name = "checked_assets")
    private Integer checkedAssets;

    @Column(name = "matched_assets")
    private Integer matchedAssets;

    @Column(name = "diff_assets")
    private Integer diffAssets;

    @Column(name = "checked_at")
    private LocalDateTime checkedAt;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        this.createdAt = LocalDateTime.now();
    }
}
