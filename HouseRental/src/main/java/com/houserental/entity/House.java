package com.houserental.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Entity
@Table(name = "houses")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class House {
    @Id
    @Column(name = "house_id", nullable = false, length = 50)
    private String houseId;

    @Column(name = "house_address", nullable = false, length = 200)
    private String houseAddress;

    @Column(name = "house_type", nullable = false, length = 50)
    private String houseType = "apartment";

    @Column(name = "house_area", nullable = false)
    private double houseArea;

    @Column(name = "house_rent", nullable = false)
    private double houseRent;

    @Column(name = "house_status", nullable = false, length = 20)
    private String houseStatus = "available";

    @ElementCollection
    @CollectionTable(name = "house_features", joinColumns = @JoinColumn(name = "house_id"))
    @Column(name = "feature")
    private List<String> houseFeatures;

    @Column(name = "landlord_id", nullable = false, length = 50)
    private String landlordId;

    @Column(name = "application_count", nullable = false)
    private int applicationCount = 0;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    @Version
    private Long version;
}
