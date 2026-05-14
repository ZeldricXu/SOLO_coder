package com.fooddelivery.entity;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import javax.persistence.*;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "regions")
public class Region {
    @Id
    @Column(name = "region_id")
    private String regionId;

    @Column(name = "region_name", nullable = false)
    private String regionName;

    @Column(name = "region_desc")
    private String regionDesc;

    @Column(name = "region_boundaries", columnDefinition = "TEXT")
    private String regionBoundaries;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
